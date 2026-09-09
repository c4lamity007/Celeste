import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.ShaderFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxMath;

/** Actual production policy: finite settings, old native ABI, world wrapping and fade bounds. */
public final class ShaderFogRulesCheck {
    private static int checks;
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,double tolerance,String message){
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,message+": "+actual+" != "+expected);
    }
    public static void main(String[] args) {
        String[] presets={"Aurora","Nebula","Sunset","Abyss","Black Hole"};
        for(int mode=0;mode<presets.length;mode++) {
            require(SkyboxMath.mode(presets[mode])==mode,"sky mapping");
            require(ShaderFogMath.mode(presets[mode],(mode+1)%4)==mode,"explicit fog preset ignores sky");
            require(ShaderFogMath.mode("Match Sky",mode)==mode,"automatic sky match");
            for(int other=mode+1;other<presets.length;other++)require(ShaderFogMath.horizon(mode)!=ShaderFogMath.horizon(other),"distinct palette");
        }
        require(ShaderFogMath.mode("Match Sky",-1)==0&&ShaderFogMath.mode("Match Sky",19)==4,"invalid sky modes clamped");
        require(ShaderFogMath.mode("bLaCk HoLe",0)==4&&ShaderFogMath.mode("match sky",4)==4,"BlackHole and matching case-insensitive");
        require(ShaderFogMath.mode(null,2)==0&&ShaderFogMath.mode("unknown",2)==0,"unknown settings fallback");
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            near(ShaderFogMath.density(value),.45f,0,"nonfinite density fallback");
            near(ShaderFogMath.distance(value),160,0,"nonfinite distance fallback");
            near(ShaderFogMath.worldCoordinate(value),0,0,"nonfinite world coordinate fallback");
            near(ShaderFogMath.opacity(value,160,.45),0,0,"nonfinite ray is invisible");
        }
        near(ShaderFogMath.density(-10),0,0,"density low clamp");near(ShaderFogMath.density(5),1,0,"density high clamp");
        near(ShaderFogMath.distance(-2),32,0,"distance low clamp");near(ShaderFogMath.distance(2048),512,0,"distance high clamp");
        for(double value:new double[]{-29999999.25,-16384,-8192.125,-8192,-.125,0,.125,8191.875,8192,29999999.25}) {
            double wrapped=ShaderFogMath.worldCoordinate(value);
            require(wrapped>=0&&wrapped<8192,"wrapped coordinate bounded");
            near(ShaderFogMath.worldCoordinate(value+8192),wrapped,.001,"world wrap period");
            near(ShaderFogMath.worldCoordinate(value-8192),wrapped,.001,"negative world wrap period");
        }
        for(double configuredDistance:new double[]{32,160,512}) {
            double previous=-1;
            for(int ray=0;ray<=2048;ray+=4) {
                double low=ShaderFogMath.opacity(ray,configuredDistance,.2),high=ShaderFogMath.opacity(ray,configuredDistance,.8);
                require(high>=0&&high<=.94,"alpha bounded");
                require(high>=previous,"distance fade monotonic");previous=high;
                require(high>=low,"density monotonic");
                near(ShaderFogMath.opacity(ray,configuredDistance,0),0,0,"density zero identity");
                if(ray<=8)near(high,0,0,"eight-block near clear radius");
                require(ShaderFogMath.opacity(ray,512,.8)<=ShaderFogMath.opacity(ray,32,.8),"distance setting range");
            }
        }
        CustomFogMath.Settings oldAbi=new CustomFogMath.Settings("Frost",.6,96);
        require(!oldAbi.shader()&&oldAbi.style().equals("Frost")&&oldAbi.density()==.6&&oldAbi.distance()==96,
            "old three-argument native settings ABI preserved");
        CustomFogMath.Settings shader=new CustomFogMath.Settings("Mist",.6,96,true,"Abyss");
        require(shader.shader()&&shader.shaderPreset().equals("Abyss"),"shader settings preserve selected preset");
        for(int bits=0;bits<128;bits++) {
            var conditions=new CustomFogMath.Conditions((bits&1)==0,(bits&2)==0,
                (bits&4)==0?CustomFogMath.Medium.AIR:CustomFogMath.Medium.WATER,
                (bits&8)!=0,(bits&16)!=0,(bits&32)!=0,(bits&64)!=0);
            require(conditions.allowed()==(bits==0),"medium/hazard/disabled gating");
        }
        System.out.println("PASS ShaderFogRules: "+checks+" production policy checks; presets, finite bounds, world wrap, alpha, safety and old native settings ABI");
    }
}
