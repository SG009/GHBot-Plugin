package dev.ghbot.web;

import dev.ghbot.builder.VoxelModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of preview jobs (bounded). Also builds the data.json payload the
 * browser viewer fetches: {"name":..., "blocks":[{x,y,z,name},...]}.
 */
public class PreviewRegistry {

    private final Map<String, PreviewJob> jobs = new LinkedHashMap<>();
    private final int maxJobs;

    public PreviewRegistry(int maxJobs) {
        this.maxJobs = Math.max(5, maxJobs);
    }

    public synchronized PreviewJob register(String name, String botId, VoxelModel model, boolean staged) {
        String id = botId.toLowerCase() + "-" + Long.toHexString(System.currentTimeMillis());
        PreviewJob job = new PreviewJob(id, name, botId, model, staged);
        jobs.put(id, job);
        while (jobs.size() > maxJobs) {
            String oldest = jobs.keySet().iterator().next();
            jobs.remove(oldest);
        }
        return job;
    }

    public synchronized PreviewJob get(String id) {
        return jobs.get(id);
    }

    public synchronized PreviewJob latest() {
        if (jobs.isEmpty()) return null;
        String last = jobs.keySet().iterator().next();
        for (String k : jobs.keySet()) last = k;
        return jobs.get(last);
    }

    public synchronized int size() {
        return jobs.size();
    }

    public synchronized Map<String, PreviewJob> all() {
        return new LinkedHashMap<>(jobs);
    }

    /** data.json payload for a job. */
    public static String dataJson(PreviewJob job) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(esc(job.name)).append("\",\"blocks\":[");
        boolean first = true;
        for (Map.Entry<Long, String> e : job.model.entries()) {
            if (!first) sb.append(',');
            first = false;
            int x = VoxelModel.xOf(e.getKey());
            int y = VoxelModel.yOf(e.getKey());
            int z = VoxelModel.zOf(e.getKey());
            sb.append("{\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z)
              .append(",\"name\":\"").append(esc(e.getValue())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
