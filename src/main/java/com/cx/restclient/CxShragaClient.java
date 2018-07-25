package com.cx.restclient;

import com.cx.restclient.common.summary.SummaryUtils;
import com.cx.restclient.configuration.CxScanConfig;
import com.cx.restclient.cxArm.dto.CxArmConfig;
import com.cx.restclient.cxArm.dto.CxProviders;
import com.cx.restclient.cxArm.dto.Policy;
import com.cx.restclient.cxArm.dto.Violation;
import com.cx.restclient.dto.Team;
import com.cx.restclient.dto.ThresholdResult;
import com.cx.restclient.exception.CxClientException;
import com.cx.restclient.exception.CxHTTPClientException;
import com.cx.restclient.httpClient.CxHttpClient;
import com.cx.restclient.osa.dto.OSAResults;
import com.cx.restclient.sast.dto.*;
import org.apache.http.client.HttpResponseException;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.cx.restclient.common.CxPARAM.*;
import static com.cx.restclient.common.ShragaUtils.isThresholdExceeded;
import static com.cx.restclient.httpClient.utils.ContentType.CONTENT_TYPE_APPLICATION_JSON_V1;
import static com.cx.restclient.httpClient.utils.HttpClientHelper.convertToJson;
import static com.cx.restclient.sast.utils.SASTParam.*;

/**
 * Created by Galn on 05/02/2018.
 */
//SHRAGA
//System Holistic Rest Api Generic Application
public class CxShragaClient {

    private CxHttpClient httpClient;
    private Logger log;
    private CxScanConfig config;
    private long projectId;

    private CxSASTClient sastClient;
    private CxOSAClient osaClient;
    private long sastScanId;
    private String osaScanId;
    private SASTResults sastResults = new SASTResults();
    private OSAResults osaResults = new OSAResults();


    public CxShragaClient(CxScanConfig config, Logger log) throws MalformedURLException {
        this.config = config;
        this.log = log;
        this.httpClient = new CxHttpClient(
                config.getUrl(),
                config.getUsername(),
                config.getPassword(),
                config.getCxOrigin(),
                config.isDisableCertificateValidation(), log);
        sastClient = new CxSASTClient(httpClient, log, config);
        osaClient = new CxOSAClient(httpClient, log, config);
    }

    public CxShragaClient(String serverUrl, String username, String password, String origin, boolean disableCertificateValidation, Logger log) throws MalformedURLException {
        this(new CxScanConfig(serverUrl, username, password, origin, disableCertificateValidation), log);
    }

    //API Scans methods
    public void init() throws CxClientException, IOException {
        log.info("Initializing Cx client");
        login();
        if (config.getSastEnabled()) {
            resolvePreset();
        }
        resolveTeam();
        resolveProject();
    }

    public long createSASTScan() throws IOException, CxClientException {
        sastScanId = sastClient.createSASTScan(projectId);
        sastResults.setSastScanLink(config.getUrl(), sastScanId, projectId);
        return sastScanId;
    }

    public String createOSAScan() throws IOException, CxClientException {
        osaScanId = osaClient.createOSAScan(projectId);
        osaResults.setOsaProjectSummaryLink(config.getUrl(), projectId);
        return osaScanId;
    }

    public void cancelSASTScan() throws IOException, CxClientException {
        sastClient.cancelSASTScan(sastScanId);
    }

    public SASTResults waitForSASTResults() throws InterruptedException, CxClientException, IOException {
        sastResults = sastClient.waitForSASTResults(sastScanId, projectId);
        return sastResults;
    }

    public SASTResults getLatestSASTResults() throws InterruptedException, CxClientException, IOException {
        sastResults = sastClient.getLatestSASTResults(projectId);
        return sastResults;
    }

    public OSAResults waitForOSAResults() throws InterruptedException, CxClientException, IOException {
        osaResults = osaClient.getOSAResults(osaScanId, projectId);
        return osaResults;
    }

    public OSAResults getLatestOSAResults() throws InterruptedException, CxClientException, IOException {
        osaResults = osaClient.getLatestOSAResults(projectId);
        return osaResults;
    }

    public ThresholdResult getThresholdResult() {
        StringBuilder res = new StringBuilder("");
        boolean isFail = isThresholdExceeded(config, sastResults, osaResults, res);
        return new ThresholdResult(isFail, res.toString());
    }

    public List<Policy> getProjectViolations() throws IOException, CxClientException {

        List<Policy> projectViolations = new ArrayList<Policy>();
        try {
            String cxARMUrl = getCxARMConfig().getCxARMPolicyURL();
            projectViolations = getProjectViolations(cxARMUrl);
            resolveProjectViolations(projectViolations);

        } catch (Exception ex) {
            log.error("CxARM is not available: " + ex.getMessage()); //TODO liran
        }

        return projectViolations;
    }

    private CxArmConfig getCxARMConfig() throws IOException, CxClientException {
        return httpClient.getRequest(CX_ARM_URL, CONTENT_TYPE_APPLICATION_JSON_V1, CxArmConfig.class, 200, "CxARM URL", false); //todo fail messgae
    }

    private void resolveProjectViolations(List<Policy> projectViolations) {
        for (Policy policy : projectViolations) {
            for (Violation violation : policy.getViolations()) {
                String provider = violation.getProvider();
                switch (CxProviders.valueOf(provider)) {
                    case OPEN_SOURCE:
                        if (config.getOsaEnabled()) {
                            osaResults.addOsaViolation(violation, policy.getPolicyName());
                        }
                        break;
                    case SAST:
                        if (config.getSastEnabled()) {

                        }
                        break;
                    default:
                        log.warn("CxARM: unknown provider type");//todo
                        break;
                }
            }
        }
    }

    public String generateHTMLSummary() throws Exception {
        return SummaryUtils.generateSummary(sastResults, osaResults, config);
    }

    public String generateHTMLSummary(SASTResults sastResults, OSAResults osaResults) throws Exception {
        return SummaryUtils.generateSummary(sastResults, osaResults, config);
    }

    public List<Project> getAllProjects() throws IOException, CxClientException {
        List<Project> projects = null;
        try {
            projects = (List<Project>) httpClient.getRequest(SAST_GET_All_PROJECTS, CONTENT_TYPE_APPLICATION_JSON_V1, Project.class, 200, "all projects", true);
        } catch (HttpResponseException ex) {
            if (ex.getStatusCode() != 404) {
                throw ex;
            }
        }
        return projects;
    }

    public void close() {
        httpClient.close();
    }

    //HELP config  Methods
    public void login() throws IOException, CxClientException {
        // perform login to server
        log.info("Logging into the Checkmarx service.");
        httpClient.login();
    }

    public String getTeamIdByName(String teamName) throws CxClientException, IOException {
        List<Team> allTeams = getTeamList();
        for (Team team : allTeams) {
            if (team.getFullName().equalsIgnoreCase(teamName)) { //TODO caseSenesitive- checkkk and REMOVE The WA "\"
                return team.getId();
            }
        }
        throw new CxClientException("Could not resolve team ID from team name: " + teamName);
    }

    public String getTeamNameById(String teamId) throws CxClientException, IOException {
        List<Team> allTeams = getTeamList();
        for (Team team : allTeams) {
            if (teamId.equals(team.getId())) {
                return team.getFullName();
            }
        }
        throw new CxClientException("Could not resolve team name from id: " + teamId);
    }

    public int getPresetIdByName(String presetName) throws CxClientException, IOException {
        List<Preset> allPresets = getPresetList();
        for (Preset preset : allPresets) {
            if (preset.getName().equalsIgnoreCase(presetName)) { //TODO caseSenesitive- checkkk
                return preset.getId();
            }
        }

        throw new CxClientException("Could not resolve preset ID from preset name: " + presetName);
    }

    public List<Team> getTeamList() throws IOException, CxClientException {
        return (List<Team>) httpClient.getRequest(CXTEAMS, CONTENT_TYPE_APPLICATION_JSON_V1, Team.class, 200, "team list", true);
    }

    public Preset getPresetById(int presetId) throws IOException, CxClientException {
        return httpClient.getRequest(CXPRESETS + "/" + presetId, CONTENT_TYPE_APPLICATION_JSON_V1, Preset.class, 200, "preset by id", false);
    }

    public List<Preset> getPresetList() throws IOException, CxClientException {
        return (List<Preset>) httpClient.getRequest(CXPRESETS, CONTENT_TYPE_APPLICATION_JSON_V1, Preset.class, 200, "preset list", true);
    }

    public List<CxNameObj> getConfigurationSetList() throws IOException, CxClientException {
        return (List<CxNameObj>) httpClient.getRequest(SAST_ENGINE_CONFIG, CONTENT_TYPE_APPLICATION_JSON_V1, CxNameObj.class, 200, "engine configurations", true);
    }

    public void setOsaFSAProperties(Properties fsaConfig){  //For CxMaven plugin
        config.setOsaFsaConfig(fsaConfig);
    }

    //Private methods
    private void resolveTeam() throws CxClientException, IOException {
        if (config.getTeamId() == null) {
            config.setTeamId(getTeamIdByName(config.getTeamPath()));
        }
        printTeamPath();
    }

    private void resolvePreset() throws CxClientException, IOException {
        if (config.getPresetId() == null) {
            config.setPresetId(getPresetIdByName(config.getPresetName()));
        }
        printPresetName();
    }

    private void printPresetName() {
        try {
            String presetName = config.getPresetName();
            if (presetName == null) {
                presetName = getPresetById(config.getPresetId()).getName();
            }
            log.info("preset name: " + presetName);
        } catch (Exception e) {
        }
    }

    private void printTeamPath() {
        try {
            String teamPath = config.getTeamPath();
            if (teamPath == null) {
                teamPath = getTeamNameById(config.getTeamId());
            }
            log.info("full team path: " + teamPath);
        } catch (Exception e) {
        }
    }

    private void resolveProject() throws IOException, CxClientException {
        List<Project> projects = getProjectByName(config.getProjectName(), config.getTeamId());
        if (projects == null || projects.isEmpty()) { // Project is new
            if (config.getDenyProject()) {
                String errMsg = "Creation of the new project [" + config.getProjectName() + "] is not authorized. " +
                        "Please use an existing project. \nYou can enable the creation of new projects by disabling" + "" +
                        " the Deny new Checkmarx projects creation checkbox in the Checkmarx plugin global settings.\n";
                throw new CxClientException(errMsg);
            }
            //Create newProject
            CreateProjectRequest request = new CreateProjectRequest(config.getProjectName(), config.getTeamId(), config.getPublic());
            projectId = createNewProject(request).getId();

        } else {
            projectId = projects.get(0).getId();
        }
    }

    private List<Project> getProjectByName(String projectName, String teamId) throws IOException, CxClientException {
        projectName = URLEncoder.encode(projectName, "UTF-8");
        String projectNamePath = SAST_GET_PROJECT.replace("{name}", projectName).replace("{teamId}", teamId);
        List<Project> projects = null;
        try {
            projects = (List<Project>) httpClient.getRequest(projectNamePath, CONTENT_TYPE_APPLICATION_JSON_V1, Project.class, 200, "project by name: " + projectName, true);
        } catch (CxHTTPClientException ex) {
            if (ex.getStatusCode() != 404) {
                throw ex;
            }
        }
        return projects;
    }

    private Project createNewProject(CreateProjectRequest request) throws CxClientException, IOException {
        String json = convertToJson(request);
        StringEntity entity = new StringEntity(json);
        return httpClient.postRequest(CREATE_PROJECT, CONTENT_TYPE_APPLICATION_JSON_V1, entity, Project.class, 201, "create new project: " + request.getName());
    }

    private List<Policy> getProjectViolations(String cxARMUrl) throws IOException, CxClientException {
        String relativePath = CX_ARM_VIOLATION.replace("{projectId}", Long.toString(projectId));
        return (List<Policy>) httpClient.getRequest(cxARMUrl, relativePath, CONTENT_TYPE_APPLICATION_JSON_V1, null, Policy.class, 200, "CxARM violations", true); //todo fail messgae
    }
}