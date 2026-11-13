package kz.zavod.studio;

public final class StudioNotFoundException extends IllegalArgumentException {
    public StudioNotFoundException(String code){super(code);}
}
