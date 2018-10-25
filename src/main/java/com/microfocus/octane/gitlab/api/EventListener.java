package com.microfocus.octane.gitlab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.DTOFactory;
import com.hp.octane.integrations.dto.causes.CIEventCause;
import com.hp.octane.integrations.dto.causes.CIEventCauseType;
import com.hp.octane.integrations.dto.events.CIEvent;
import com.hp.octane.integrations.dto.events.CIEventType;
import com.hp.octane.integrations.dto.events.PhaseType;
import com.hp.octane.integrations.dto.scm.*;
import com.hp.octane.integrations.dto.snapshots.CIBuildResult;
import com.microfocus.octane.gitlab.helpers.GitLabApiWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.CompareResults;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.Job;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
@Path("/events")
public class EventListener {
    private static final Logger log = LogManager.getLogger(EventListener.class);
    private static final DTOFactory dtoFactory = DTOFactory.getInstance();
    private final GitLabApi gitLabApi;

    @Autowired
    public EventListener(GitLabApiWrapper gitLabApiWrapper) {
        this.gitLabApi = gitLabApiWrapper.getGitLabApi();
    }

    @POST
    @Produces("application/json")
    @Consumes("application/json")
    public Response index(String msg) {
        JSONObject obj = new JSONObject(msg);
        handleEvent(obj);
        return Response.status(200).build();
    }

    private void handleEvent(JSONObject obj) {
        log.traceEntry();
        try {
            CIEventType eventType = getEventType(obj);
            if (eventType == CIEventType.UNDEFINED || eventType == CIEventType.QUEUED) return;
            List<CIEvent> eventList = getCIEvents(obj);
            eventList.forEach(event -> {
                if (event.getResult() == null) {
                    event.setResult(CIBuildResult.UNAVAILABLE);
                }
                try {
                    log.trace(new ObjectMapper().writeValueAsString(event));
                } catch (Exception e) {
                    log.debug("Failed to trace an incoming event", e);
                }
                OctaneSDK.getInstance().getEventsService().publishEvent(event);
            });
            if (eventType == CIEventType.FINISHED) {
                Integer projectId = isPipelineEvent(obj) ? obj.getJSONObject("project").getInt("id") : obj.getInt("project_id");
                if (!isPipelineEvent(obj)) {
                    Integer jobId = getObjectId(obj);
                    Job job = gitLabApi.getJobApi().getJob(projectId, jobId);
                    if (job.getArtifactsFile() != null) {
                        OctaneSDK.getInstance().getTestsService().enqueuePushTestsResult(projectId.toString(), jobId.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("An error occurred while handling GitLab event", e);
        }
        log.traceExit();
    }

    private List<CIEvent> getCIEvents(JSONObject obj) {
        List<CIEvent> events = new ArrayList<>();
        CIEventType eventType = getEventType(obj);
        Integer buildCiId = getObjectId(obj);
        Long startTime = getTime(obj, "started_at");
        if (startTime == null) startTime = getTime(obj, "created_at");
        Object duration = getDuration(obj);
        SCMData scmData = null;
        boolean isScmNull = true;
        if (isPipelineEvent(obj)) {
            if (eventType == CIEventType.STARTED) {
                scmData = getScmData(obj);
                isScmNull = scmData == null;
            } else {
                String GITLAB_BLANK_SHA = "0000000000000000000000000000000000000000";
                isScmNull = obj.getJSONObject("object_attributes").getString("before_sha").equals(GITLAB_BLANK_SHA);
            }
        }

        events.add(dtoFactory.newDTO(CIEvent.class)
                .setProjectDisplayName(getCiName(obj))
                .setEventType(eventType)
                .setBuildCiId(buildCiId.toString())
                .setNumber(buildCiId.toString())
                .setProject(getCiFullName(obj))
                .setResult(eventType == CIEventType.STARTED ? null : convertCiBuildResult(getStatus(obj)))
                .setStartTime(startTime)
                .setEstimatedDuration(null)
                .setDuration(eventType == CIEventType.STARTED ? null : duration != null ? Math.round(duration instanceof Double ? (Double) duration : (Integer) duration) : null)
                .setScmData(null)
                .setCauses(getCauses(obj, isScmNull))
                .setPhaseType(isPipelineEvent(obj) ? PhaseType.POST : PhaseType.INTERNAL)
        );

        if (scmData != null) {
            events.add(dtoFactory.newDTO(CIEvent.class)
                    .setProjectDisplayName(getCiName(obj))
                    .setEventType(CIEventType.SCM)
                    .setBuildCiId(buildCiId.toString())
                    .setNumber(null)
                    .setProject(getCiFullName(obj))
                    .setResult(null)
                    .setStartTime(null)
                    .setEstimatedDuration(null)
                    .setDuration(null)
                    .setScmData(scmData)
                    .setCauses(getCauses(obj, false))
                    .setPhaseType(null)
            );
        }

        return events;
    }

    private List<CIEventCause> getCauses(JSONObject obj, boolean isScmNull) {
        List<CIEventCause> causes = new ArrayList<>();
        CIEventCauseType type = convertCiEventCauseType(obj, isScmNull);
        CIEventCause rootCause = dtoFactory.newDTO(CIEventCause.class);
        rootCause.setType(type);
        rootCause.setUser(type == CIEventCauseType.USER ? getUser(obj) : null);
        if (isPipelineEvent(obj)) {
            causes.add(rootCause);
        } else {
            CIEventCause cause = dtoFactory.newDTO(CIEventCause.class);
            cause.setType(CIEventCauseType.UPSTREAM);
            cause.setProject(getRootFullName(obj)); ///
            cause.setBuildCiId(getRootId(obj).toString());
            cause.getCauses().add(rootCause);
            causes.add(cause);
        }
        return causes;
    }

    private String getUser(JSONObject obj) {
        return obj.getJSONObject("user").getString("name");
    }

    private CIEventCauseType convertCiEventCauseType(JSONObject obj, boolean isScmNull) {
        if (isScmNull) {
            String pipelineSchedule = null;
            try {
                pipelineSchedule = isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getString("pipeline_schedule") : null;
            } catch (Exception e) {
                log.debug("Failed to infer event cause type, using 'USER' as default", e);
            }
            if (pipelineSchedule != null && pipelineSchedule.equals("true")) return CIEventCauseType.TIMER;
            return CIEventCauseType.USER;
        }
        return CIEventCauseType.SCM;
    }

    private CIBuildResult convertCiBuildResult(String status) {
        if (status.equals("success")) return CIBuildResult.SUCCESS;
        if (status.equals("failed")) return CIBuildResult.FAILURE;
        if (status.equals("drop") || status.equals("skipped") || status.equals("canceled"))
            return CIBuildResult.ABORTED;
        if (status.equals("unstable")) return CIBuildResult.UNSTABLE;
        return CIBuildResult.UNAVAILABLE;
    }

    private String getStatus(JSONObject obj) {
        return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getString("status") : obj.getString("build_status");
    }

    private String getCiName(JSONObject obj) {
        return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getString("ref") : obj.getString("build_name");
    }

    private String getCiFullName(JSONObject obj) {
        String fullName = getProjectFullPath(obj) + "/" + getCiName(obj);
        if (isPipelineEvent(obj)) fullName = "pipeline:" + fullName;
        return fullName;
    }

    private String getRootName(JSONObject obj) {
        return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getString("ref") : obj.getString("ref");
    }

    private String getRootFullName(JSONObject obj) {
        return "pipeline:" + getProjectFullPath(obj) + "/" + getRootName(obj);
    }

    private String getProjectFullPath(JSONObject obj) {
        try {
            if (isPipelineEvent(obj)) {
                return obj.getJSONObject("project").getString("namespace") + "/" + obj.getJSONObject("project").getString("name");
            }
            return new URL(obj.getJSONObject("repository").getString("homepage")).getPath().substring(1);
        } catch (MalformedURLException e) {
            log.debug("Failed to return the project full path, using an empty string as default", e);
            return "";
        }
    }

    private Integer getRootId(JSONObject obj) {
        return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getInt("id") : obj.getJSONObject("commit").getInt("id");
    }

    private SCMData getScmData(JSONObject obj) {
        try {
            Integer projectId = obj.getJSONObject("project").getInt("id");
            String sha = obj.getJSONObject("object_attributes").getString("sha");
            String beforeSha = obj.getJSONObject("object_attributes").getString("before_sha");
            CompareResults results = gitLabApi.getRepositoryApi().compare(projectId, beforeSha, sha);
            List<SCMCommit> commits = new ArrayList<>();
            results.getCommits().forEach(c -> {
                SCMCommit commit = dtoFactory.newDTO(SCMCommit.class);
                commit.setTime(c.getTimestamp() != null ? c.getTimestamp().getTime() : new Date().getTime());
                commit.setUser(c.getCommitterName());
                commit.setUserEmail(c.getCommitterEmail());
                commit.setRevId(c.getId());
                commit.setParentRevId(sha);
                commit.setComment(c.getMessage());
                try {
                    List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, c.getId());
                    List<SCMChange> changes = new ArrayList<>();
                    diffs.forEach(d -> {
                        SCMChange change = dtoFactory.newDTO(SCMChange.class);
                        change.setFile(d.getNewPath());
                        change.setType(d.getNewFile() ? "add" : d.getDeletedFile() ? "delete" : "edit");
                        changes.add(change);
                    });
                    commit.setChanges(changes);
                } catch (GitLabApiException e) {
                    log.debug("Failed to add a commit to the SCM data", e);
                }
                commits.add(commit);
            });

            SCMRepository repo = dtoFactory.newDTO(SCMRepository.class);
            repo.setType(SCMType.GIT);
            repo.setUrl(obj.getJSONObject("project").getString("git_http_url"));
            repo.setBranch(obj.getJSONObject("object_attributes").getString("ref"));
            SCMData data = dtoFactory.newDTO(SCMData.class);
            data.setRepository(repo);
            data.setBuiltRevId(sha);
            data.setCommits(commits);
            return data;
        } catch (GitLabApiException e) {
            log.debug("Failed to return the SCM data. Returning null.");
            return null;
        }
    }

    private Object getDuration(JSONObject obj) {
        try {
            return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").get("duration") : obj.get("build_duration");
        } catch (Exception e) {
            log.debug("Failed to return the duration, using null as default.", e);
            return null;
        }
    }

    private Long getTime(JSONObject obj, String attrName) {
        try {
            String time = isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getString(attrName) : obj.getString("build_" + attrName);
            return time == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH).parse(time).getTime();
        } catch (Exception e) {
            log.debug("Failed to return the time, using null as default.", e);
            return null;
        }
    }

    private Integer getObjectId(JSONObject obj) {
        return isPipelineEvent(obj) ? obj.getJSONObject("object_attributes").getInt("id") : obj.getInt("build_id");
    }

    private CIEventType getEventType(JSONObject obj) {
        String statusStr;
        if (isPipelineEvent(obj)) {
            statusStr = obj.getJSONObject("object_attributes").getString("status");
            if (statusStr.equals("pending")) return CIEventType.STARTED;
            if (statusStr.equals("running")) return CIEventType.UNDEFINED;
        } else {
            statusStr = obj.getString("build_status");
        }
        if (Arrays.asList(new String[]{"process", "enqueue", "pending", "created"}).contains(statusStr)) {
            return CIEventType.QUEUED;
        } else if (Arrays.asList(new String[]{"success", "failed", "canceled", "skipped"}).contains(statusStr)) {
            return CIEventType.FINISHED;
        } else if (Arrays.asList(new String[]{"running", "manual"}).contains(statusStr)) {
            return CIEventType.STARTED;
        } else {
            return CIEventType.UNDEFINED;
        }
    }

    private boolean isPipelineEvent(JSONObject obj) {
        return obj.getString("object_kind").equals("pipeline");
    }
}
