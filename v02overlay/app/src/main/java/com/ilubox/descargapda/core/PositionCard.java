package com.ilubox.descargapda.core;

public class PositionCard {
    public String label;
    public String state;
    public String title;
    public String detail;
    public boolean enabled;
    public int slot;
    public String side;
    public boolean waitingRemoval;
    public int codeCount;
    public int boxCount;
    public double actualCbm;

    public PositionCard(String label, String state, String title, String detail,
                        boolean enabled, int slot, String side, boolean waitingRemoval,
                        int codeCount, int boxCount, double actualCbm) {
        this.label = label;
        this.state = state;
        this.title = title;
        this.detail = detail;
        this.enabled = enabled;
        this.slot = slot;
        this.side = side;
        this.waitingRemoval = waitingRemoval;
        this.codeCount = codeCount;
        this.boxCount = boxCount;
        this.actualCbm = actualCbm;
    }
}
