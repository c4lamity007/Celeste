import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightMesh.Cuboid;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Standalone production-geometry regression: javac with SolidVisualMesh + BlockHighlightMesh. */
public final class BlockHighlightMeshCheck {
    private static int assertions;
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-9, message + ": " + actual + " != " + expected);
    }
    private static Point sub(Point a, Point b) { return new Point(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()); }
    private static Point cross(Point a, Point b) {
        return new Point(a.y()*b.z()-a.z()*b.y(), a.z()*b.x()-a.x()*b.z(), a.x()*b.y()-a.y()*b.x());
    }
    private static double dot(Point a, Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static boolean contains(List<Cuboid> boxes, Point point) {
        return boxes.stream().anyMatch(b -> point.x()>b.minX() && point.x()<b.maxX()
            && point.y()>b.minY() && point.y()<b.maxY() && point.z()>b.minZ() && point.z()<b.maxZ());
    }
    private static List<Face> check(List<Cuboid> boxes, double expectedArea, double expectedVolume, String label) {
        List<Face> faces = BlockHighlightMesh.surface(boxes, BlockHighlightMesh.MAX_FACES);
        require(!faces.isEmpty() && faces.size() <= 192, label + " missing or unbounded mesh");
        double area=0, volume=0;
        for (Face face : faces) {
            Point normal = cross(sub(face.b(),face.a()),sub(face.d(),face.a()));
            double length = Math.sqrt(dot(normal, normal));
            require(Double.isFinite(length) && length > 0, label + " degenerate face");
            area += length;
            volume += (dot(face.a(),cross(face.b(),face.c()))+dot(face.a(),cross(face.c(),face.d())))/6;
            // Use an irrational interior sample to avoid unrelated box-grid seams.
            Point center = new Point(face.a().x()+.371*(face.b().x()-face.a().x())+.613*(face.d().x()-face.a().x()),
                face.a().y()+.371*(face.b().y()-face.a().y())+.613*(face.d().y()-face.a().y()),
                face.a().z()+.371*(face.b().z()-face.a().z())+.613*(face.d().z()-face.a().z()));
            Point inside = new Point(center.x()-normal.x()/length*1e-7,center.y()-normal.y()/length*1e-7,center.z()-normal.z()/length*1e-7);
            Point outside = new Point(center.x()+normal.x()/length*1e-7,center.y()+normal.y()/length*1e-7,center.z()+normal.z()/length*1e-7);
            require(contains(boxes,inside), label + " inward sample outside union (bad winding/hole)");
            require(!contains(boxes,outside), label + " interior face retained");
            require(face.color()==-1 && face.endColor()==-1, label + " unexpected geometry tint");
        }
        near(area,expectedArea,label+" exposed area");
        near(volume,expectedVolume,label+" enclosed volume");
        List<Cuboid> reversed = new ArrayList<>(boxes);
        Collections.reverse(reversed);
        require(faces.equals(BlockHighlightMesh.surface(reversed,192)), label + " depends on input box order");
        return faces;
    }
    private static void standardShapes() {
        List<Cuboid> cube = List.of(new Cuboid(0,0,0,1,1,1));
        require(check(cube,6,1,"Cube").size()==6,"Cube must merge to six faces");
        require(check(List.of(new Cuboid(0,0,0,1,.5,1)),4,.5,"Bottom slab").size()==6,"Slab face count");
        check(List.of(new Cuboid(0,.5,0,1,1,1)),4,.5,"Top slab");
        List<Cuboid> stairs = List.of(new Cuboid(0,0,0,1,.5,1),new Cuboid(0,.5,.5,1,1,1));
        List<Face> stairMesh = check(stairs,5.5,.75,"Straight stairs");
        require(stairMesh.size()==10,"Stair faces should merge to ten, not twelve overlapping box faces");
        require(BlockHighlightMesh.surface(stairs,9).isEmpty(),"A short surface budget must not truncate stairs");
        // Overlapping, adjacent and redundant boxes resolve to one complete union.
        require(check(List.of(new Cuboid(0,0,0,.75,1,1),new Cuboid(.25,0,0,1,1,1)),6,1,"Overlap").size()==6,"Overlap unmerged");
        require(check(List.of(new Cuboid(0,0,0,.5,1,1),new Cuboid(.5,0,0,1,1,1)),6,1,"Adjacent").size()==6,"Interior adjacent face retained");
        require(check(List.of(cube.getFirst(),cube.getFirst(),new Cuboid(.25,.25,.25,.75,.75,.75)),6,1,"Redundant").size()==6,"Contained cube retained");
        check(List.of(new Cuboid(-.25,0,0,.25,1.5,.25)),2.5,.1875,"Outside-block fence post");
        check(List.of(new Cuboid(-0.0,0,0,.5,1,1),new Cuboid(+0.0,0,0,1,1,1)),6,1,"Signed zero");
        check(List.of(new Cuboid(-.002,-.002,-.002,1.002,1.002,1.002)),6*1.004*1.004,
            1.004*1.004*1.004,"Terrain-depth surface offset");
        List<Cuboid> inflatedStairs = stairs.stream().map(b -> new Cuboid(b.minX()-.002,b.minY()-.002,b.minZ()-.002,
            b.maxX()+.002,b.maxY()+.002,b.maxZ()+.002)).toList();
        double width=1.004,half=.504,overlap=.004;
        double inflatedVolume=width*half*width + width*half*half - width*overlap*half;
        check(inflatedStairs,2*(width*width+width*width+width*width-.5*.5),
            inflatedVolume,"Inflated stair union");
    }
    private static void voxelOracle() {
        Random random = new Random(0xB10C);
        for (int trial=0;trial<48;trial++) {
            boolean[][][] cells = new boolean[4][4][4];
            List<Cuboid> boxes = new ArrayList<>();
            for (int x=0;x<4;x++) for (int y=0;y<4;y++) for (int z=0;z<4;z++) {
                cells[x][y][z] = random.nextBoolean();
                if (cells[x][y][z]) boxes.add(new Cuboid(x*.25,y*.25,z*.25,(x+1)*.25,(y+1)*.25,(z+1)*.25));
            }
            int exposed=0;
            for (int x=0;x<4;x++) for (int y=0;y<4;y++) for (int z=0;z<4;z++) if(cells[x][y][z]) {
                exposed += x==0 || !cells[x-1][y][z] ? 1 : 0;
                exposed += x==3 || !cells[x+1][y][z] ? 1 : 0;
                exposed += y==0 || !cells[x][y-1][z] ? 1 : 0;
                exposed += y==3 || !cells[x][y+1][z] ? 1 : 0;
                exposed += z==0 || !cells[x][y][z-1] ? 1 : 0;
                exposed += z==3 || !cells[x][y][z+1] ? 1 : 0;
            }
            check(boxes,exposed/16.0,boxes.size()/64.0,"Voxel union "+trial);
        }
    }
    private static void budgetsAndTransforms() {
        List<Cuboid> disconnected = new ArrayList<>();
        for(int x=0;x<4;x++) for(int y=0;y<4;y++) for(int z=0;z<2;z++)
            disconnected.add(new Cuboid(x*.25,y*.25,z*.25,x*.25+.125,y*.25+.125,z*.25+.125));
        List<Face> exact=check(disconnected,3,.0625,"Exact 192-face cap");
        require(exact.size()==192,"Exact cap rejected");
        require(BlockHighlightMesh.surface(disconnected,191).isEmpty(),"Partial 191-face mesh emitted");
        require(BlockHighlightMesh.surface(disconnected,Integer.MAX_VALUE).size()==192,"Cap overflow");
        disconnected.add(new Cuboid(1.125,1.125,1.125,1.25,1.25,1.25));
        require(BlockHighlightMesh.surface(disconnected,10000).isEmpty(),"Hard face cap exceeded");
        require(BlockHighlightMesh.materialize(exact,0,0,0,0x7F80CAFF,191).isEmpty(),"Shared short budget emits open shape");
        List<Face> transformed=BlockHighlightMesh.materialize(exact,30_000_000,-64,-30_000_000,0x7F80CAFF,192);
        require(transformed.size()==192,"Complete exact-budget materialization absent");
        for(int i=0;i<exact.size();i++) {
            Face before=exact.get(i),after=transformed.get(i);
            near(after.a().x(),before.a().x()+30_000_000,"World X translation");
            near(after.c().y(),before.c().y()-64,"World Y translation");
            near(after.d().z(),before.d().z()-30_000_000,"World Z translation");
            require(after.color()==0x7F80CAFF && after.endColor()==after.color(),"Selected opacity/color altered per face");
        }
        require(BlockHighlightMesh.materialize(exact,0,0,0,0x00FFFFFF,192).isEmpty(),"Zero opacity retained");
        require(BlockHighlightMesh.materialize(exact,Double.NaN,0,0,-1,192).isEmpty(),"NaN world offset accepted");
        require(BlockHighlightMesh.materialize(exact,Double.POSITIVE_INFINITY,0,0,-1,192).isEmpty(),"Infinite offset accepted");
        require(BlockHighlightMesh.materialize(exact,0,0,0,-1,-1).isEmpty(),"Negative budget accepted");
        require(BlockHighlightMesh.materialize(List.of(),0,0,0,-1,192).isEmpty(),"Empty cached mesh retained geometry");
    }
    private static void invalidInput() {
        require(BlockHighlightMesh.surface(null,192).isEmpty(),"Null shape accepted");
        require(BlockHighlightMesh.surface(List.of(),192).isEmpty(),"Empty shape accepted");
        require(BlockHighlightMesh.surface(Collections.nCopies(97,new Cuboid(0,0,0,1,1,1)),192).isEmpty(),"Box cap ignored");
        require(BlockHighlightMesh.surface(List.of(new Cuboid(0,0,0,0,1,1)),192).isEmpty(),"Degenerate box accepted");
        require(BlockHighlightMesh.surface(List.of(new Cuboid(0,0,0,Double.NaN,1,1)),192).isEmpty(),"NaN box accepted");
        require(BlockHighlightMesh.surface(List.of(new Cuboid(0,0,0,1,Double.POSITIVE_INFINITY,1)),192).isEmpty(),"Infinite box accepted");
        require(BlockHighlightMesh.surface(List.of(new Cuboid(0,0,0,17,1,1)),192).isEmpty(),"Unbounded local extent accepted");
        List<Cuboid> manyPlanes=new ArrayList<>();
        for(int i=0;i<17;i++)manyPlanes.add(new Cuboid(i/20.0,0,0,(i+.5)/20.0,1,1));
        require(BlockHighlightMesh.surface(manyPlanes,192).isEmpty(),"Axis-cell allocation cap ignored");
    }
    public static void main(String[] args) {
        standardShapes(); voxelOracle(); budgetsAndTransforms(); invalidInput();
        System.out.println("PASS BlockHighlightMesh: "+assertions+" assertions; exact cube/slab/stairs, merged union surface/winding/volume, 48 voxel oracles, 192-face atomic budget, tint/translation and invalid-input caps.");
    }
}
