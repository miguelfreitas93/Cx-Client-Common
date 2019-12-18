package com.cx.restclient;

import com.cx.restclient.common.DependencyScanner;
import com.cx.restclient.common.UrlUtils;
import com.cx.restclient.common.Waiter;
import com.cx.restclient.configuration.CxScanConfig;
import com.cx.restclient.dto.DependencyScanResults;
import com.cx.restclient.dto.LoginSettings;
import com.cx.restclient.dto.PathFilter;
import com.cx.restclient.exception.CxClientException;
import com.cx.restclient.httpClient.CxHttpClient;
import com.cx.restclient.httpClient.utils.ContentType;
import com.cx.restclient.httpClient.utils.HttpClientHelper;
import com.cx.restclient.osa.dto.ClientType;
import com.cx.restclient.sast.utils.zip.CxZipUtils;
import com.cx.restclient.sca.SCAWaiter;
import com.cx.restclient.sca.dto.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SCA - Software Composition Analysis - is the successor of OSA.
 */
public class SCAClient implements DependencyScanner {
    private static class UrlPaths {
        private static final String RISK_MANAGEMENT_API = "/risk-management/api/";
        private static final String PROJECTS = RISK_MANAGEMENT_API + "projects";
        private static final String SUMMARY_REPORT = RISK_MANAGEMENT_API + "riskReports/%s/summary";
        private static final String SCAN_STATUS = RISK_MANAGEMENT_API + "scans/%s/status";
        private static final String REPORT_ID = RISK_MANAGEMENT_API + "scans/%s/riskReportId";

        private static final String ZIP_UPLOAD = "/emerald/api/scans/zip";

        private static final String WEB_REPORT = "/#/projects/%s/reports/%s";
    }

    private final Logger log;
    private final CxScanConfig config;

    // This class uses its own instance of CxHttpClient, because SCA has a different base URL and Access Control server.
    private final CxHttpClient httpClient;

    private String projectId;
    private final Waiter<ScanStatusResponse> waiter;
    private String scanId;

    SCAClient(CxScanConfig config, Logger log) throws CxClientException {
        this.log = log;
        this.config = config;

        int pollInterval = config.getOsaProgressInterval() != null ? config.getOsaProgressInterval() : 20;
        int maxRetries = config.getConnectionRetries() != null ? config.getConnectionRetries() : 3;

        SCAConfig scaConfig = getScaConfig();

        httpClient = new CxHttpClient(scaConfig.getApiUrl(),
                config.getCxOrigin(),
                config.isDisableCertificateValidation(),
                config.isUseSSOLogin(),
                null,
                config.getProxyConfig(),
                log);

        waiter = new SCAWaiter("CxSCA scan", pollInterval, maxRetries, httpClient, UrlPaths.SCAN_STATUS, log);
    }

    @Override
    public void init() throws CxClientException {
        try {
            login();
            resolveProject();
        } catch (IOException e) {
            throw new CxClientException("Failed to init CxSCA Client.", e);
        }
    }

    @Override
    public String createScan(DependencyScanResults target) throws CxClientException {
        log.info("----------------------------------- Create CxSCA Scan:------------------------------------");

        PathFilter filter = new PathFilter(config.getOsaFolderExclusions(), config.getOsaFilterPattern(), log);
        scanId = null;
        try {
            String sourceDir = config.getEffectiveSourceDirForDependencyScan();
            File zipFile = CxZipUtils.getZippedSources(config, filter, sourceDir, log);
            scanId = uploadZipFile(zipFile);
            CxZipUtils.deleteZippedSources(zipFile, config, log);
        } catch (IOException e) {
            throw new CxClientException("Error creating CxSCA scan.", e);
        }

        return scanId;
    }

    @Override
    public void waitForScanResults(DependencyScanResults target) throws CxClientException {
        log.info("------------------------------------Get CxSCA Results:-----------------------------------");

        log.info("Waiting for CxSCA scan to finish");
        waiter.waitForTaskToFinish(scanId, this.config.getOsaScanTimeoutInMinutes(), log);

        log.info("CxSCA scan finished successfully. Retrieving CxSCA scan results.");
        SCAResults scaResult;
        try {
            scaResult = retrieveScanResults();
        } catch (IOException e) {
            throw new CxClientException("Error retrieving CxSCA scan results.", e);
        }

        if (!StringUtils.isEmpty(scaResult.getWebReportLink())) {
            log.info("CxSCA scan results location: " + scaResult.getWebReportLink());
        }

        target.setScaResults(scaResult);
    }

    @Override
    public DependencyScanResults getLatestScanResults() {
        // TODO
        return null;
    }

    void testConnection() throws IOException, CxClientException {
        // The calls below allow to check both access control and API connectivity.
        login();
        getProjects();
    }

    private void login() throws IOException, CxClientException {
        log.info("Logging into CxSCA.");
        SCAConfig scaConfig = getScaConfig();

        LoginSettings settings = new LoginSettings();
        settings.setAccessControlBaseUrl(scaConfig.getAccessControlUrl());
        settings.setUsername(scaConfig.getUsername());
        settings.setPassword(scaConfig.getPassword());
        settings.setTenant(scaConfig.getTenant());
        settings.setClientTypeForPasswordAuth(ClientType.SCA_CLI);

        httpClient.login(settings);
    }

    private void resolveProject() throws IOException, CxClientException {
        String projectName = config.getProjectName();
        projectId = getProjectIdByName(projectName);
        if (projectId == null) {
            log.debug("Project not found, creating a new one.");
            projectId = createProject(projectName);
        }
        log.debug("Using project ID: " + projectId);
    }

    private String getProjectIdByName(String name) throws IOException, CxClientException {
        log.debug("Getting project by name: " + name);

        if (StringUtils.isEmpty(name)) {
            throw new CxClientException("Non-empty project name must be provided.");
        }

        List<Project> allProjects = getProjects();

        return allProjects.stream()
                .filter((Project project) -> name.equals(project.getName()))
                .map(Project::getId)
                .findFirst()
                .orElse(null);
    }

    private List<Project> getProjects() throws IOException, CxClientException {
        return (List<Project>) httpClient.getRequest(UrlPaths.PROJECTS,
                    ContentType.CONTENT_TYPE_APPLICATION_JSON,
                    Project.class,
                    HttpStatus.SC_OK,
                    "CxSCA projects",
                    true);
    }

    private String createProject(String name) throws CxClientException, IOException {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName(name);

        StringEntity entity = HttpClientHelper.convertToStringEntity(request);

        Project newProject = httpClient.postRequest(UrlPaths.PROJECTS,
                ContentType.CONTENT_TYPE_APPLICATION_JSON,
                entity,
                Project.class,
                HttpStatus.SC_CREATED,
                "create a project");

        return newProject.getId();
    }

    private String uploadZipFile(File zipFile) throws IOException, CxClientException {
        log.info("Uploading zipped sources.");

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        InputStream input = new FileInputStream(zipFile.getAbsoluteFile());
        InputStreamBody fileBody = new InputStreamBody(input, org.apache.http.entity.ContentType.APPLICATION_OCTET_STREAM, "zippedSource");
        builder.addPart("zipFile", fileBody);

        ContentBody projectIdBody = new StringBody(projectId, org.apache.http.entity.ContentType.APPLICATION_FORM_URLENCODED);
        builder.addPart("projectId", projectIdBody);

        HttpEntity entity = builder.build();

        String scanId = httpClient.postRequest(UrlPaths.ZIP_UPLOAD, null, entity, String.class, HttpStatus.SC_CREATED, "upload ZIP file");
        log.debug("Scan ID: " + scanId);

        return scanId;
    }

    private SCAResults retrieveScanResults() throws IOException, CxClientException {
        String reportId = getReportId();

        SCAResults result = new SCAResults();
        result.setScanId(scanId);

        SCASummaryResults scanSummary = getSummaryReport(reportId);
        result.setSummary(scanSummary);

        String reportLink = getWebReportLink(reportId);
        result.setWebReportLink(reportLink);
        return result;
    }

    private String getWebReportLink(String reportId) {
        String MESSAGE = "Unable to generate web report link. ";
        String result = null;
        try {
            String webAppUrl = getScaConfig().getWebAppUrl();
            if (StringUtils.isEmpty(webAppUrl)) {
                log.warn(MESSAGE + "Web app URL is not specified.");
            } else {
                String encoding = StandardCharsets.UTF_8.name();
                String path = String.format(UrlPaths.WEB_REPORT,
                        URLEncoder.encode(projectId, encoding),
                        URLEncoder.encode(reportId, encoding));

                result = UrlUtils.parseURLToString(webAppUrl, path);
            }
        } catch (MalformedURLException e) {
            log.warn(MESSAGE + "Invalid web app URL.", e);
        } catch (Exception e) {
            log.warn(MESSAGE, e);
        }
        return result;
    }

    private String getReportId() throws IOException, CxClientException {
        log.debug("Getting report ID by scan ID: " + scanId);
        String path = String.format(UrlPaths.REPORT_ID, scanId);
        String reportId = httpClient.getRequest(path,
                ContentType.CONTENT_TYPE_APPLICATION_JSON,
                String.class,
                HttpStatus.SC_OK,
                "Risk report ID",
                false);
        log.debug("Found report ID: " + reportId);
        return reportId;
    }

    private SCASummaryResults getSummaryReport(String reportId) throws IOException, CxClientException {
        log.debug("Getting summary report.");

        String path = String.format(UrlPaths.SUMMARY_REPORT, reportId);

        SCASummaryResults result = httpClient.getRequest(path,
                ContentType.CONTENT_TYPE_APPLICATION_JSON,
                SCASummaryResults.class,
                HttpStatus.SC_OK,
                "CxSCA report summary",
                false);

        printSummary(result);

        return result;
    }

    // This method is for demo purposes and probably should be replaced in the future.
    private void printSummary(SCASummaryResults summary) {
        log.info("\n----CxSCA risk report summary----");
        log.info("Created on: " + summary.getCreatedOn());
        log.info("Direct packages: " + summary.getDirectPackages());
        log.info("High vulnerabilities: " + summary.getHighVulnerabilityCount());
        log.info("Medium vulnerabilities: " + summary.getMediumVulnerabilityCount());
        log.info("Low vulnerabilities: " + summary.getLowVulnerabilityCount());
        log.info("Risk report ID: " + summary.getRiskReportId());
        log.info("Risk score: " + summary.getRiskScore());
        log.info("Total packages: " + summary.getTotalPackages());
        log.info(String.format("Total outdated packages: %d\n", summary.getTotalOutdatedPackages()));
    }

    private SCAConfig getScaConfig() throws CxClientException {
        SCAConfig result = config.getScaConfig();
        if (result == null) {
            throw new CxClientException("CxSCA scan configuration is missing.");
        }
        return result;
    }
}