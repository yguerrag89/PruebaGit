package com.ilubox.descargapda.core;

import java.io.Serializable;

public class Settings implements Serializable {
    private static final long serialVersionUID = 1L;

    public double physicalCapacity = 2.16;
    public double targetCapacity = 1.94;
    public double largeRatio = 0.70;
    public double mediumHighRatio = 0.45;
    public double mediumRatio = 0.25;
    public int maxCodesUnit = 20;
    public int maxCodesSmall = 4;
    public int maxCodesMedium = 3;
    public int maxCodesMediumHigh = 2;

    public String categoryFor(double cbm, boolean unitCode) {
        if (unitCode) return "U";
        double ratio = targetCapacity > 0 ? cbm / targetCapacity : 1.0;
        if (ratio >= largeRatio) return "G";
        if (ratio >= mediumHighRatio) return "M1";
        if (ratio >= mediumRatio) return "M2";
        return "P";
    }

    public int maxCodesFor(String category) {
        if ("U".equals(category)) return maxCodesUnit;
        if ("G".equals(category)) return 1;
        if ("M1".equals(category)) return maxCodesMediumHigh;
        if ("M2".equals(category)) return maxCodesMedium;
        return maxCodesSmall;
    }
}
