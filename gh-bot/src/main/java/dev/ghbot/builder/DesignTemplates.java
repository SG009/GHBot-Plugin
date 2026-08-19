package dev.ghbot.builder;

import java.util.List;

/** Built-in design templates — used when no AI is configured (fallback). */
public final class DesignTemplates {

    private DesignTemplates() {}

    public static DesignSpec pick(String prompt) {
        String p = (prompt == null ? "" : prompt.toLowerCase());
        if (p.contains("tower") || p.contains("watchtower")) return tower();
        if (p.contains("castle") || p.contains("fort") || p.contains("keep")) return castle();
        if (p.contains("ship") || p.contains("boat") || p.contains("sail")) return ship();
        if (p.contains("lighthouse")) return lighthouse();
        if (p.contains("windmill") || p.contains("mill")) return windmill();
        if (p.contains("barn") || p.contains("farmhouse")) return barn();
        if (p.contains("fountain")) return fountain();
        if (p.contains("bridge")) return bridge();
        if (p.contains("gate") || p.contains("archway")) return gateway();
        if (p.contains("tree") || p.contains("oak") || p.contains("forest")) return tree();
        if (p.contains("path") || p.contains("road")) return path();
        if (p.contains("plaza") || p.contains("square") || p.contains("market")) return plaza();
        if (p.contains("hut") || p.contains("cabin") || p.contains("cottage")) return hut();
        if (p.contains("large") || p.contains("big") || p.contains("mansion")) return mansion();
        return house();
    }

    public static DesignSpec house() {
        DesignSpec s = new DesignSpec();
        s.name = "Small House"; s.style = "medieval";
        s.palette.addAll(List.of("oak_planks", "dark_oak_planks", "glass", "spruce_planks"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","11","d","9","y","0","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","11","h","5","d","9","y","1","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","4","y","1","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        s.ops.add(new DesignSpec.Op("window_row", java.util.Map.of("cx","0","cz","4","y","2","count","3","spacing","2","face","south","frame","dark_oak_planks","glass","glass")));
        s.ops.add(new DesignSpec.Op("cone", java.util.Map.of("cx","0","cz","0","radius","6","height","3","y","6","mat","spruce_planks")));
        return s;
    }

    public static DesignSpec tower() {
        DesignSpec s = new DesignSpec();
        s.name = "Wizard Tower"; s.style = "fantasy";
        s.palette.addAll(List.of("stone_bricks", "dark_oak_planks", "glass", "prismarine"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","13","d","13","y","0","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","0","cz","0","radius","5","height","10","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("window_row", java.util.Map.of("cx","0","cz","5","y","4","count","3","spacing","2","face","south","frame","dark_oak_planks","glass","glass")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","5","y","1","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        s.ops.add(new DesignSpec.Op("dome", java.util.Map.of("cx","0","cy","11","cz","0","radius","5","mat","prismarine")));
        return s;
    }

    public static DesignSpec castle() {
        DesignSpec s = new DesignSpec();
        s.name = "Mini Castle"; s.style = "medieval";
        s.palette.addAll(List.of("stone_bricks", "dark_oak_planks", "glass", "cobblestone"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","19","d","15","y","0","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","19","h","6","d","15","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","-9","cz","-7","radius","3","height","8","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","9","cz","-7","radius","3","height","8","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","-9","cz","7","radius","3","height","8","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","9","cz","7","radius","3","height","8","y","1","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","7","y","1","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        return s;
    }

    public static DesignSpec hut() {
        DesignSpec s = new DesignSpec();
        s.name = "Forest Hut"; s.style = "rustic";
        s.palette.addAll(List.of("oak_log", "oak_planks", "spruce_planks", "glass"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","7","d","7","y","0","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","7","h","4","d","7","y","1","mat","oak_log")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","3","y","1","face","south","mat","dark_oak_door","frame","oak_planks")));
        s.ops.add(new DesignSpec.Op("window_row", java.util.Map.of("cx","0","cz","3","y","2","count","2","spacing","2","face","south","frame","oak_planks","glass","glass")));
        s.ops.add(new DesignSpec.Op("cone", java.util.Map.of("cx","0","cz","0","radius","4","height","2","y","5","mat","spruce_planks")));
        return s;
    }

    public static DesignSpec mansion() {
        DesignSpec s = new DesignSpec();
        s.name = "Mansion"; s.style = "modern";
        s.palette.addAll(List.of("quartz_block", "glass", "dark_oak_planks"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","21","d","15","y","0","mat","quartz_block")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","21","h","6","d","15","y","1","mat","quartz_block")));
        s.ops.add(new DesignSpec.Op("window_row", java.util.Map.of("cx","0","cz","7","y","3","count","5","spacing","2","face","south","frame","dark_oak_planks","glass","glass")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","7","y","1","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","11","d","7","y","7","mat","quartz_block")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","11","h","4","d","7","y","8","mat","quartz_block")));
        return s;
    }

    public static DesignSpec ship() {
        DesignSpec s = new DesignSpec();
        s.name = "Sailing Ship"; s.style = "adventure";
        s.palette.addAll(List.of("oak_planks", "dark_oak_planks", "oak_log", "white_wool"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","15","d","6","y","0","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","15","h","3","d","6","y","1","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","0","z","0","y0","4","y1","12","mat","oak_log")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","-4","z","0","y0","4","y1","10","mat","oak_log")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","4","z","0","y0","4","y1","10","mat","oak_log")));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","3","y","1","radius","2","mat","dark_oak_planks")));
        return s;
    }

    public static DesignSpec lighthouse() {
        DesignSpec s = new DesignSpec();
        s.name = "Lighthouse"; s.style = "coastal";
        s.palette.addAll(List.of("quartz_block", "red_concrete", "glowstone", "glass"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","9","d","9","y","0","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","0","cz","0","radius","3","height","10","y","1","mat","quartz_block")));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","0","y","4","radius","4","mat","red_concrete")));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","0","y","7","radius","4","mat","red_concrete")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","0","z","0","y0","11","y1","13","mat","quartz_block")));
        s.ops.add(new DesignSpec.Op("set", java.util.Map.of("x","0","y","14","z","0","mat","glowstone")));
        return s;
    }

    public static DesignSpec windmill() {
        DesignSpec s = new DesignSpec();
        s.name = "Windmill"; s.style = "rustic";
        s.palette.addAll(List.of("cobblestone", "oak_planks", "dark_oak_log", "white_wool"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","11","d","11","y","0","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("tower", java.util.Map.of("cx","0","cz","0","radius","4","height","8","y","1","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("cone", java.util.Map.of("cx","0","cz","0","radius","4","height","3","y","9","mat","dark_oak_log")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","7","h","3","d","7","y","1","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","-5","z","0","y0","1","y1","8","mat","dark_oak_log")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","5","z","0","y0","1","y1","8","mat","dark_oak_log")));
        return s;
    }

    public static DesignSpec barn() {
        DesignSpec s = new DesignSpec();
        s.name = "Barn"; s.style = "rustic";
        s.palette.addAll(List.of("red_concrete", "oak_planks", "dark_oak_planks", "hay_block"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","15","d","11","y","0","mat","oak_planks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","15","h","5","d","11","y","1","mat","red_concrete")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","5","y","1","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        s.ops.add(new DesignSpec.Op("cone", java.util.Map.of("cx","0","cz","0","radius","8","height","4","y","6","mat","dark_oak_planks")));
        return s;
    }

    public static DesignSpec fountain() {
        DesignSpec s = new DesignSpec();
        s.name = "Fountain"; s.style = "garden";
        s.palette.addAll(List.of("stone_bricks", "water", "glowstone"));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","0","y","0","radius","5","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","0","y","1","radius","5","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","9","d","9","y","0","mat","water")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","0","z","0","y0","1","y1","4","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("set", java.util.Map.of("x","0","y","5","z","0","mat","glowstone")));
        return s;
    }

    public static DesignSpec bridge() {
        DesignSpec s = new DesignSpec();
        s.name = "Stone Bridge"; s.style = "medieval";
        s.palette.addAll(List.of("stone_bricks", "cobblestone", "dark_oak_planks"));
        s.ops.add(new DesignSpec.Op("path", java.util.Map.of("x0","-8","z0","0","x1","8","z1","2","y","0","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","17","h","1","d","3","y","0","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","1","w","17","h","1","d","1","y","1","mat","dark_oak_planks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","-6","z","-2","y0","-3","y1","0","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","6","z","-2","y0","-3","y1","0","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","-6","z","4","y0","-3","y1","0","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","6","z","4","y0","-3","y1","0","mat","stone_bricks")));
        return s;
    }

    public static DesignSpec gateway() {
        DesignSpec s = new DesignSpec();
        s.name = "Gateway"; s.style = "medieval";
        s.palette.addAll(List.of("stone_bricks", "dark_oak_planks", "cobblestone"));
        s.ops.add(new DesignSpec.Op("wall", java.util.Map.of("x0","-3","z0","-1","x1","-1","z1","1","y0","0","y1","6","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("wall", java.util.Map.of("x0","1","z0","-1","x1","3","z1","1","y0","0","y1","6","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("box", java.util.Map.of("cx","0","cz","0","w","7","h","2","d","3","y","6","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("door", java.util.Map.of("cx","0","cz","0","y","0","face","south","mat","dark_oak_door","frame","dark_oak_planks")));
        return s;
    }

    public static DesignSpec tree() {
        DesignSpec s = new DesignSpec();
        s.name = "Big Oak"; s.style = "nature";
        s.palette.addAll(List.of("oak_log", "oak_leaves"));
        s.ops.add(new DesignSpec.Op("tree", java.util.Map.of("x","0","z","0","y","1","height","5","log","oak_log","leaves","oak_leaves")));
        s.ops.add(new DesignSpec.Op("tree", java.util.Map.of("x","2","z","2","y","1","height","3","log","oak_log","leaves","oak_leaves")));
        return s;
    }

    public static DesignSpec path() {
        DesignSpec s = new DesignSpec();
        s.name = "Gravel Path"; s.style = "utility";
        s.palette.addAll(List.of("gravel"));
        s.ops.add(new DesignSpec.Op("path", java.util.Map.of("x0","0","z0","0","x1","10","z1","0","y","0","mat","gravel")));
        s.ops.add(new DesignSpec.Op("path", java.util.Map.of("x0","0","z0","-1","x1","10","z1","-1","y","0","mat","gravel")));
        return s;
    }

    public static DesignSpec plaza() {
        DesignSpec s = new DesignSpec();
        s.name = "Town Plaza"; s.style = "medieval";
        s.palette.addAll(List.of("cobblestone", "stone_bricks", "lantern"));
        s.ops.add(new DesignSpec.Op("floor", java.util.Map.of("cx","0","cz","0","w","21","d","21","y","0","mat","cobblestone")));
        s.ops.add(new DesignSpec.Op("ring", java.util.Map.of("cx","0","cz","0","y","0","radius","6","mat","stone_bricks")));
        s.ops.add(new DesignSpec.Op("column", java.util.Map.of("x","0","z","0","y0","1","y1","3","mat","stone_bricks")));
        return s;
    }
}
