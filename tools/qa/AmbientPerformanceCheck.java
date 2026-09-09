import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientGeometry;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Actual helper equivalence/culling checks and bounded CPU-only throughput/allocation samples. */
public final class AmbientPerformanceCheck {
    static final int[][] FACES = {{0,3,2},{0,4,3},{0,5,4},{0,2,5},{1,2,3},{1,3,4},{1,4,5},{1,5,2}};
    static int checks;
    static volatile double sink;
    static volatile Object escaped;
    record V(double x, double y, double z) {
        V add(V b) { return new V(x+b.x,y+b.y,z+b.z); }
        V subtract(V b) { return new V(x-b.x,y-b.y,z-b.z); }
        V cross(V b) { return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x); }
        V normalize() { double length=Math.sqrt(x*x+y*y+z*z); double inverse=1/length; return length<1E-5 ? new V(0,0,0) : new V(x*inverse,y*inverse,z*inverse); }
    }
    static void require(boolean value,String text) { checks++; if(!value)throw new AssertionError(text); }
    static void exact(double a,double b,String text) { require(a==b || Double.doubleToLongBits(a)==Double.doubleToLongBits(b),text+": "+a+" != "+b); }
    static V[] reference(double px,double py,double pz,double radius,double stretch,double yaw,double pitch) {
        double sy=Math.sin(yaw),cy=Math.cos(yaw),sp=Math.sin(pitch),cp=Math.cos(pitch);
        V[] vertices={new V(0,radius*stretch,0),new V(0,-radius*stretch,0),new V(radius,0,0),new V(0,0,radius),new V(-radius,0,0),new V(0,0,-radius)};
        V center=new V(px,py,pz);
        for(int i=0;i<vertices.length;i++) {
            V v=vertices[i];double y=v.y*cp-v.z*sp,z=v.y*sp+v.z*cp;
            vertices[i]=new V(v.x*cy-z*sy,y,v.x*sy+z*cy).add(center);
        }
        return vertices;
    }
    static void geometry() {
        Random random=new Random(78132);double[] scratch=new double[AmbientGeometry.SCRATCH_SIZE];
        for(int i=0;i<2400;i++) {
            double px=(random.nextDouble()-.5)*6E7,py=random.nextDouble()*384-64,pz=(random.nextDouble()-.5)*6E7;
            double r=.015+random.nextDouble()*.35,stretch=new double[]{1,.35,2.2}[i%3],yaw=random.nextDouble()*400,pitch=random.nextDouble()*400;
            V[] expected=reference(px,py,pz,r,stretch,yaw,pitch);
            AmbientGeometry.core(scratch,px,py,pz,r,stretch,yaw,pitch);
            for(int j=0;j<6;j++) { exact(scratch[j*3],expected[j].x,"core x");exact(scratch[j*3+1],expected[j].y,"core y");exact(scratch[j*3+2],expected[j].z,"core z"); }
            for(int[] face:FACES) {
                V n=expected[face[1]].subtract(expected[face[0]]).cross(expected[face[2]].subtract(expected[face[0]])).normalize();
                AmbientGeometry.normal(scratch,face[0],face[1],face[2]);
                exact(scratch[18],n.x,"normal x");exact(scratch[19],n.y,"normal y");exact(scratch[20],n.z,"normal z");
            }
        }
    }
    static void visibility() {
        int occupied=0, candidates=0, retained=0;
        Random random=new Random(4937);
        for(int eye=0;eye<75;eye++) {
            double ex=(random.nextDouble()-.5)*2000,ey=random.nextDouble()*350-30,ez=(random.nextDouble()-.5)*2000;
            int cx=(int)Math.floor(ex/5),cy=(int)Math.floor(ey/5),cz=(int)Math.floor(ez/5);
            for(int x=cx-5;x<=cx+5;x++)for(int z=cz-5;z<=cz+5;z++)for(int y=cy-3;y<=cy+3;y++) {
                if(!AmbientField.occupied(x,y,z,.6))continue;
                occupied++;boolean candidate=AmbientField.mayBeVisible(x,y,z,ex,ey,ez);if(candidate)candidates++;
                for(AmbientField.Style style:AmbientField.Style.values())for(double t:new double[]{0,.016,9.5,254.99,255.01}) {
                    var mote=AmbientField.sample(x,y,z,style,t);
                    double alpha=mote.opacity()*AmbientField.visibility(mote.x()-ex,mote.y()-ey,mote.z()-ez);
                    require(candidate || alpha==0,"Cell cull removed a visible mote");
                    if(alpha>=.01)retained++;
                }
            }
        }
        System.out.printf(Locale.ROOT,"Ambient candidates: %d/%d occupied cells retained (%.1f%% fewer motion samples), %d visible samples preserved exactly.%n",candidates,occupied,100.0*(occupied-candidates)/occupied,retained);
    }
    static double field(boolean filtered,int frames,double density) {
        double result=0;
        for(int f=0;f<frames;f++) {
            double ex=102.35+(f%120)*.012,ey=72.45,ez=-151.23;int cx=(int)Math.floor(ex/5),cy=(int)Math.floor(ey/5),cz=(int)Math.floor(ez/5);
            for(int x=cx-5;x<=cx+5;x++)for(int z=cz-5;z<=cz+5;z++)for(int y=cy-3;y<=cy+3;y++) {
                if(!AmbientField.occupied(x,y,z,density) || filtered&&!AmbientField.mayBeVisible(x,y,z,ex,ey,ez))continue;
                var mote=AmbientField.sample(x,y,z,AmbientField.Style.FIREFLIES,f/60.0);
                double alpha=mote.opacity()*AmbientField.visibility(mote.x()-ex,mote.y()-ey,mote.z()-ez);
                if(alpha>=.01)result+=alpha+mote.size();
            }
        }
        return result;
    }
    static void mesh(boolean optimized,int frames) {
        double result=0;double[] scratch=new double[AmbientGeometry.SCRATCH_SIZE];
        for(int f=0;f<frames;f++)for(int p=0;p<128;p++) {
            double yaw=(f+p)*.005,pitch=(f-p)*.003;
            if(optimized) {
                AmbientGeometry.core(scratch,p*.15,72,p*.12,.08,2.2,yaw,pitch);
                for(int[] face:FACES) { AmbientGeometry.normal(scratch,face[0],face[1],face[2]);result+=scratch[18]+scratch[19]+scratch[20]; }
            } else {
                V[] vertices=reference(p*.15,72,p*.12,.08,2.2,yaw,pitch);escaped=vertices;
                for(int[] face:FACES) { V n=vertices[face[1]].subtract(vertices[face[0]]).cross(vertices[face[2]].subtract(vertices[face[0]])).normalize();result+=n.x+n.y+n.z; }
            }
        }
        sink=result;
    }
    static void benchmark() {
        for(boolean fast:List.of(false,true)) { sink=field(fast,750,.6);mesh(fast,350); }
        for(double density:new double[]{.6,1}) {
            double base=field(false,600,density),fast=field(true,600,density);exact(base,fast,"Field aggregate/order changed");
            long[] old=new long[5],next=new long[5];int frames=1400;
            for(int i=0;i<5;i++) {
                long start=System.nanoTime();sink=field(false,frames,density);old[i]=System.nanoTime()-start;
                start=System.nanoTime();sink=field(true,frames,density);next[i]=System.nanoTime()-start;
            }
            Arrays.sort(old);Arrays.sort(next);
            System.out.printf(Locale.ROOT,"CPU ambient field density %.1f: baseline %.3f -> filtered %.3f ms/frame, %.2fx throughput (median five runs; no world/GPU/FPS claim).%n",density,old[2]/1E6/frames,next[2]/1E6/frames,(double)old[2]/next[2]);
        }
        var bean=ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
            long old=allocation.getThreadAllocatedBytes(thread);mesh(false,300);old=allocation.getThreadAllocatedBytes(thread)-old;
            long next=allocation.getThreadAllocatedBytes(thread);mesh(true,300);next=allocation.getThreadAllocatedBytes(thread)-next;
            System.out.printf(Locale.ROOT,"CPU 128-mote core scratch allocation: baseline %d -> primitive %d bytes/frame (reference array deliberately retained; JIT can eliminate some temporary vectors in game).%n",old/300,next/300);
            require(next<old,"Primitive mesh did not reduce measured allocation");
        }
    }
    public static void main(String[] args) {
        geometry();visibility();benchmark();
        System.out.println("PASS Ambient performance: "+checks+" checks; exact transformed vertices/normals at world bounds, conservative culling across styles/times, same sample order. Game/FPS verification separate.");
    }
}
