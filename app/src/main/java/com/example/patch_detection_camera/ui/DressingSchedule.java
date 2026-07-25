package com.example.patch_detection_camera.ui;

import java.util.HashMap;
import java.util.Map;

public class DressingSchedule {
    public static final int CYCLE_HOURS = 6;

    public long id;             // PK: lastChangedAt timestamp
    public long lastChangedAt;
    public long nextChangeAt;
    public boolean isActive;
    public long createdAt;

    public DressingSchedule() {}

    public DressingSchedule(long lastChangedAt) {
        this.id            = lastChangedAt;
        this.lastChangedAt = lastChangedAt;
        this.nextChangeAt  = lastChangedAt + (long) CYCLE_HOURS * 3600 * 1000;
        this.isActive      = true;
        this.createdAt     = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id",            id);
        m.put("cycleHours",    CYCLE_HOURS);
        m.put("lastChangedAt", lastChangedAt);
        m.put("nextChangeAt",  nextChangeAt);
        m.put("isActive",      isActive);
        m.put("createdAt",     createdAt);
        return m;
    }

    public static DressingSchedule fromMap(Map<String, Object> m) {
        DressingSchedule ds = new DressingSchedule();
        Object id  = m.get("id");
        if (id instanceof Long) ds.id = (Long) id;
        Object lca = m.get("lastChangedAt");
        if (lca instanceof Long) ds.lastChangedAt = (Long) lca;
        Object nca = m.get("nextChangeAt");
        if (nca instanceof Long) ds.nextChangeAt = (Long) nca;
        Object active = m.get("isActive");
        if (active instanceof Boolean) ds.isActive = (Boolean) active;
        Object ca = m.get("createdAt");
        if (ca instanceof Long) ds.createdAt = (Long) ca;
        return ds;
    }
}
