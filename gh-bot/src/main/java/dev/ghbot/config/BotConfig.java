package dev.ghbot.config;

/** Per-bot configuration block (mirrors your old settings.json "bot-accounts"). */
public class BotConfig {
    private String id = "GH000";
    private String provider = "auto";
    private int queueSize = 8;
    private int maxBuildSize = 60;
    private String mode = "review";
    private boolean animate = false;
    private final Avatar avatar = new Avatar();
    private boolean debuglog = false;
    private String role = "general";

    public static class Avatar {
        private boolean enabled = false;
        private String type = "enderman";
        private boolean stay = false;

        public boolean enabled() { return enabled; }
        public String type() { return type; }
        public boolean stay() { return stay; }

        void setEnabled(boolean v) { enabled = v; }
        void setType(String v) { type = v; }
        void setStay(boolean v) { stay = v; }
    }

    public String id() { return id; }
    public String provider() { return provider; }
    public int queueSize() { return queueSize; }
    public int maxBuildSize() { return maxBuildSize; }
    public String mode() { return mode; }
    public boolean animate() { return animate; }
    public Avatar avatar() { return avatar; }
    public boolean debuglog() { return debuglog; }
    public String role() { return role; }

    public void setId(String v) { id = v; }
    public void setProvider(String v) { provider = v; }
    public void setQueueSize(int v) { queueSize = v; }
    public void setMaxBuildSize(int v) { maxBuildSize = v; }
    public void setMode(String v) { mode = v; }
    public void setAnimate(boolean v) { animate = v; }
    public void setDebuglog(boolean v) { debuglog = v; }
    public void setRole(String v) { role = v; }
}
