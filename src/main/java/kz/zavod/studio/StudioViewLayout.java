package kz.zavod.studio;

import java.util.*;

public final class StudioViewLayout {
    private StudioViewLayout(){}
    public record Position(double x,double y) {
        public Position {
            if(!validCoordinate(x)||!validCoordinate(y))
                throw new IllegalArgumentException("INVALID_CANVAS_COORDINATE");
        }
    }
    public record Viewport(double x,double y,double zoom) {
        public Viewport {
            if(!validCoordinate(x)||!validCoordinate(y)||!Double.isFinite(zoom)
                    ||zoom<0.05||zoom>8)
                throw new IllegalArgumentException("INVALID_CANVAS_VIEWPORT");
        }
    }
    public record LayoutBody(String unitId,Map<String,Position> positions,Viewport viewport) {
        public LayoutBody {
            Objects.requireNonNull(unitId);Objects.requireNonNull(positions);Objects.requireNonNull(viewport);
            if(unitId.isBlank()||positions.size()>2000)
                throw new IllegalArgumentException("INVALID_CANVAS_LAYOUT");
            positions=Map.copyOf(positions);
        }
    }
    public record SaveLayout(long expectedLayoutVersion,long blueprintRevision,LayoutBody layout){
        public SaveLayout {
            if(expectedLayoutVersion<0||blueprintRevision<1||layout==null)
                throw new IllegalArgumentException("INVALID_CANVAS_VERSION");
        }
    }
    public record LayoutResponse(String viewId,long layoutVersion,long blueprintRevision,LayoutBody layout){}
    private static boolean validCoordinate(double x){return Double.isFinite(x)&&Math.abs(x)<=1_000_000;}
}
