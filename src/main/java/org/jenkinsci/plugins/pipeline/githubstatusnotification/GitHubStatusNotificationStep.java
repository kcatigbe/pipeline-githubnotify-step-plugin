/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.pipeline.githubstatusnotification;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.github.*;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.IOException;
import java.net.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;

/**
 * A pipeline step that allows to send a commit status to GitHub.
 * <p>
 * See <a href="https://developer.github.com/v3/repos/statuses/">GitHub's Statuses API</a>
 */
public final class GitHubStatusNotificationStep extends AbstractStepImpl {

    public static final String CREDENTIALS_NOT_FOUND = "The credentials were not found.  Please check them";
    public static final String CREDENTIALS_NULL = "Credentials were null or empty";
    public static final String CREDENTIALS_INVALID = "The supplied credentials are invalid to login";
    public static final String CREDENTIALS_UNSUPPORTED = "Sorry, the supplied type of credentials are not supported";
    public static final String INVALID_REPO = "The specified repository does not exist.  Please ensure the supplied credentials have access to it";
    public static final String INVALID_COMMIT = "The specified commit does not exist in the specified repository";

    /**
     * The commit status to send with the notification
     */
    private GHCommitState status;
    /**
     * A short description of the status to send
     */
    private String description;
    /**
     * A string label to differentiate the send status from the status of other systems.
     */
    private String context = DescriptorImpl.context;
    /**
     * The repository that owns the commit to notify
     */
    private String repo;
    /**
     * The commit to notify unique sha1, used as commit identifier
     */
    private String sha;
    /**
     * The optional GitHub enterprise instance api url endpoint.
     * <p>
     * Used when you are using your own GitHub enterprise instance instead of the default GitHub SaaS (http://github.com)
     */
    private String gitApiUrl = DescriptorImpl.gitApiUrl;
    /**
     * The id of the jenkins stored credentials to use to connect to GitHub, must identify a UsernamePassword credential
     */
    private String credentialsId;
    /**
     * The target URL to associate with the sendstatus.
     * <p>
     * This URL will be linked from the GitHub UI to allow users to easily see the 'source' of the Status.
     */
    private String targetUrl = DescriptorImpl.targetUrl;

    @DataBoundConstructor
    public GitHubStatusNotificationStep(GHCommitState status, String description) {
        this.status = status;
        this.description = description;
    }

    @DataBoundSetter
    public void setGitApiUrl(String gitApiUrl) {
        this.gitApiUrl = gitApiUrl;
    }

    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public void setStatus(GHCommitState status) {
        this.status = status;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @DataBoundSetter
    public void setContext(String context) {
        this.context = context;
    }

    @DataBoundSetter
    public void setRepo(String repo) {
        this.repo = repo;
    }

    @DataBoundSetter
    public void setSha(String sha) {
        this.sha = sha;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    public GHCommitState getStatus() {
        return this.status;
    }

    public String getDescription() {
        return this.description;
    }

    public String getContext() {
        return this.context;
    }

    public String getRepo() {
        return this.repo;
    }

    public String getSha() {
        return this.sha;
    }

    public String getGitApiUrl() {
        return this.gitApiUrl;
    }

    public String getCredentialsId() {
        return this.credentialsId;
    }

    public String getTargetUrl() {
        return this.targetUrl;
    }

    private static <T extends Credentials> T getCredentials(@Nonnull Class<T> type, @Nonnull String credentialsId, Item context) {
        List<T> credentialsList = lookupCredentials(type,
                context,
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList());

        return CredentialsMatchers.firstOrNull(credentialsList, CredentialsMatchers.allOf(
                CredentialsMatchers.withId(credentialsId),
                CredentialsMatchers.instanceOf(type)));
    }

    /**
     * Uses proxy if configured on pluginManager/advanced page
     *
     * @param host GitHub's hostname to build proxy to
     * @return proxy to use it in connector. Should not be null as it can lead to unexpected behaviour
     */
    @Nonnull
    private static Proxy getProxy(@Nonnull String host) {
        Jenkins jenkins = Jenkins.getActiveInstance();

        if (jenkins.proxy == null) {
            return Proxy.NO_PROXY;
        } else {
            return jenkins.proxy.createProxy(host);
        }
    }

    private static GitHub getGitHubIfValid(String credentialsId, String gitApiUrl, Item context) throws IOException {
        if (credentialsId == null || credentialsId.isEmpty()) {
            throw new IllegalArgumentException(CREDENTIALS_NULL);
        }
        Credentials credentials = getCredentials(Credentials.class, credentialsId, context);
        if (credentials == null) {
            throw new IllegalArgumentException(CREDENTIALS_NOT_FOUND);
        }
        GitHubBuilder githubBuilder = new GitHubBuilder();

        if (credentials instanceof UsernamePasswordCredentials) {
            UsernamePasswordCredentials pwdCredentials = ((UsernamePasswordCredentials) credentials);
            githubBuilder.withOAuthToken(pwdCredentials.getPassword().getPlainText(), pwdCredentials.getUsername());
        } else if (credentials instanceof StringCredentials) {
            githubBuilder.withOAuthToken(((StringCredentials) credentials).getSecret().getPlainText());
        } else {
            throw new IllegalArgumentException(CREDENTIALS_UNSUPPORTED);
        }

        if (gitApiUrl == null || gitApiUrl.isEmpty()) {
            githubBuilder = githubBuilder.withProxy(getProxy("https://api.github.com"));
        } else {
            githubBuilder = githubBuilder.withEndpoint(gitApiUrl);
            githubBuilder = githubBuilder.withProxy(getProxy(gitApiUrl));
        }

        GitHub github = githubBuilder.build();

        if (github.isCredentialValid()) {
            return github;
        } else {
            throw new IllegalArgumentException(CREDENTIALS_INVALID);
        }
    }

    private static GHRepository getRepoIfValid(String credentialsId, String gitApiUrl, String repo, Item context) throws IOException {
        GitHub github = getGitHubIfValid(credentialsId, gitApiUrl, context);

        GHRepository repository = github.getMyself().getAllRepositories().get(repo);

        if (repository == null) {
            throw new IllegalArgumentException(INVALID_REPO);
        }
        return repository;
    }

    private static GHCommit getCommitIfValid(String credentialsId, String gitApiUrl, String repo, String sha, Item context) throws IOException {
        GHRepository repository = getRepoIfValid(credentialsId, gitApiUrl, repo, context);
        GHCommit commit = repository.getCommit(sha);
        if (commit == null) {
            throw new IllegalArgumentException(INVALID_COMMIT);
        }
        return commit;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public static final String gitApiUrl = null;
        public static final String targetUrl = null;
        public static final String context = "jenkins/githubnotify";


        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override
        public String getFunctionName() {
            return "githubNotify";
        }

        @Override
        public String getDisplayName() {
            return "Notifies GitHub of the status of a Pull Request";
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project) {
            AbstractIdCredentialsListBoxModel result = new StandardListBoxModel();
            List<Credentials> credentialsList = CredentialsProvider
                    .lookupCredentials(Credentials.class, project, ACL.SYSTEM, Collections.emptyList())
                    .stream()
                    .filter(cred -> (cred instanceof UsernamePasswordCredentials) || (cred instanceof StringCredentials))
                    .collect(Collectors.toList());

            for (Credentials credential : credentialsList) {
                result = result.with((IdCredentials) credential);
            }
            return result;
        }

        public ListBoxModel doFillStatusItems() {
            ListBoxModel list = new ListBoxModel();
            for (GHCommitState state : GHCommitState.values()) {
                list.add(state.name(), state.name());
            }
            return list;
        }

        public FormValidation doTestConnection(@QueryParameter("credentialsId") final String credentialsId, @QueryParameter("gitApiUrl") final String gitApiUrl, @AncestorInPath Item context) {
            try {
                getGitHubIfValid(credentialsId, gitApiUrl, context);
                return FormValidation.ok("Success");
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckRepo(@QueryParameter("credentialsId") final String credentialsId,
                                          @QueryParameter("repo") final String repo, @QueryParameter("gitApiUrl") final String gitApiUrl, @AncestorInPath Item context) {
            try {
                getRepoIfValid(credentialsId, gitApiUrl, repo, context);
                return FormValidation.ok("Success");
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckSha(@QueryParameter("credentialsId") final String credentialsId, @QueryParameter("repo") final String repo,
                                         @QueryParameter("sha") final String sha, @QueryParameter("gitApiUrl") final String gitApiUrl, @AncestorInPath Item context) {
            try {
                getCommitIfValid(credentialsId, gitApiUrl, repo, sha, context);
                return FormValidation.ok("Commit seems valid");

            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }

    public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        public static final String UNABLE_TO_INFER_DATA = "Unable to infer git data, please specify repo, credentialsId and sha values";
        public static final String UNABLE_TO_INFER_COMMIT = "Could not infer exact commit to use, please specify one";
        public static final String UNABLE_TO_INFER_CREDENTIALS_ID = "Can not infer exact credentialsId to use, please specify one";

        @Inject
        private transient GitHubStatusNotificationStep step;

        @StepContextParameter
        private transient Run run;

        @Override
        protected Void run() throws Exception {
            String targetUrl = getTargetUrl();
            String credentialsId = getCredentialsId();
            String repo = getRepo();
            GHRepository repository = getRepoIfValid(credentialsId, step.getGitApiUrl(), repo, run.getParent());
            String sha1 = getSha1();
            GHCommit commit = null;
            try {
                commit = repository.getCommit(sha1);
            } catch (IOException ex) {
                throw new IllegalArgumentException(INVALID_COMMIT, ex);
            }
            repository.createCommitStatus(commit.getSHA1(),
                    step.getStatus(), targetUrl, step.getDescription(), step.getContext());
            return null;
        }

        public GitHubStatusNotificationStep getStep() {
            return step;
        }

        public void setStep(GitHubStatusNotificationStep step) {
            this.step = step;
        }

        public Run getRun() {
            return run;
        }

        public void setRun(Run run) {
            this.run = run;
        }

        private static final long serialVersionUID = 1L;

        private String getTargetUrl() {
            return (step.getTargetUrl() == null || step.getTargetUrl().isEmpty()) ? DisplayURLProvider.get().getRunURL(run) : step.getTargetUrl();
        }

        private String getCredentialsId() {
            if (step.getCredentialsId() == null || step.getCredentialsId().isEmpty()) {
                return tryToInferCredentialsId();
            } else {
                return step.getCredentialsId();
            }
        }

        private String getRepo() {
            if (step.getRepo() == null || step.getRepo().isEmpty()) {
                return tryToInferRepo();
            } else {
                return step.getRepo();
            }
        }

        private String getSha1() {
            if (step.getSha() == null || step.getSha().isEmpty()) {
                return tryToInferSha();
            } else {
                return step.getSha();
            }
        }

        private String tryToInferCredentialsId() {
            String credentialsID = getSource().getScanCredentialsId();
            if (credentialsID != null) {
                return credentialsID;
            } else {
                throw new IllegalArgumentException(UNABLE_TO_INFER_CREDENTIALS_ID);
            }
        }

        private String tryToInferRepo() {
            return getSource().getRepository();
        }

        private String tryToInferSha() {
            SCMRevisionAction action = run.getAction(SCMRevisionAction.class);
            if (action != null) {
                SCMRevision revision = action.getRevision();
                if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl) {
                    return ((AbstractGitSCMSource.SCMRevisionImpl) revision).getHash();
                } else if (revision instanceof PullRequestSCMRevision) {
                    return ((PullRequestSCMRevision) revision).getPullHash();
                } else {
                    throw new IllegalArgumentException(UNABLE_TO_INFER_COMMIT);
                }
            } else {
                throw new IllegalArgumentException(UNABLE_TO_INFER_COMMIT);
            }
        }

        private GitHubSCMSource getSource() {
            ItemGroup parent = run.getParent().getParent();
            if (parent instanceof SCMSourceOwner) {
                SCMSourceOwner owner = (SCMSourceOwner) parent;
                for (SCMSource source : owner.getSCMSources()) {
                    if (source instanceof GitHubSCMSource) {
                        return ((GitHubSCMSource) source);
                    }
                }
                throw new IllegalArgumentException(UNABLE_TO_INFER_DATA);
            } else {
                throw new IllegalArgumentException(UNABLE_TO_INFER_DATA);
            }
        }
    }
}
