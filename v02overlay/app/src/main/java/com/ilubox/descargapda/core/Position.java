package com.ilubox.descargapda.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Position implements Serializable {
    private static final long serialVersionUID = 2L;

    public final String side;
    public final int slot;
    public boolean enabled;
    public String kind = "LIBRE";
    public int palletSeq = 0;
    public double reservedCbm = 0.0;
    public double actualCbm = 0.0;
    public List<String> reservedCodes = new ArrayList<>();
    public Set<String> completeCodes = new HashSet<>();
    /** Cantidad física registrada por código en la tarima ACTUAL. */
    public Map<String, Integer> boxesByCodeOnCurrentPallet = new LinkedHashMap<>();
    public String dedicatedCode = null;
    public Integer palletBoxCapacity = null;
    public Integer palletTargetBoxes = null;
    public int boxesOnCurrentPallet = 0;
    public boolean waitingRemoval = false;
    public String removalReason = "";
    public boolean manuallyClosed = false;

    public Position(String side, int slot, boolean enabled) {
        this.side = side;
        this.slot = slot;
        this.enabled = enabled;
    }

    public String label() {
        return String.format("%s%02d", side, slot);
    }

    public boolean isFree() {
        return enabled && "LIBRE".equals(kind) && !waitingRemoval;
    }

    public int boxesForCode(String code) {
        Integer n = boxesByCodeOnCurrentPallet.get(code);
        return n == null ? 0 : n;
    }

    public void addBox(String code) {
        boxesOnCurrentPallet += 1;
        boxesByCodeOnCurrentPallet.put(code, boxesForCode(code) + 1);
    }

    public void removeBox(String code) {
        boxesOnCurrentPallet = Math.max(0, boxesOnCurrentPallet - 1);
        int n = boxesForCode(code);
        if (n <= 1) boxesByCodeOnCurrentPallet.remove(code);
        else boxesByCodeOnCurrentPallet.put(code, n - 1);
    }

    public void resetKeepingIdentity() {
        kind = "LIBRE";
        reservedCbm = 0.0;
        actualCbm = 0.0;
        reservedCodes.clear();
        completeCodes.clear();
        boxesByCodeOnCurrentPallet.clear();
        dedicatedCode = null;
        palletBoxCapacity = null;
        palletTargetBoxes = null;
        boxesOnCurrentPallet = 0;
        waitingRemoval = false;
        removalReason = "";
        manuallyClosed = false;
    }
}
