package com.ilubox.descargapda.core;

public class ActionResult {
    public boolean ok;
    public String position = "";
    public String message = "";
    public boolean continues = false;

    public ActionResult(boolean ok, String position, String message, boolean continues) {
        this.ok = ok;
        this.position = position == null ? "" : position;
        this.message = message == null ? "" : message;
        this.continues = continues;
    }
}
