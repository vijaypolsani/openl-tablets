package org.openl.rules.repository.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.hooks.CommitMsgHook;
import org.eclipse.jgit.hooks.PreCommitHook;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeMessageFormatter;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.openl.rules.repository.api.BranchRepository;
import org.openl.rules.repository.api.ChangesetType;
import org.openl.rules.repository.api.ConflictResolveData;
import org.openl.rules.repository.api.Features;
import org.openl.rules.repository.api.FeaturesBuilder;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.FileItem;
import org.openl.rules.repository.api.FolderItem;
import org.openl.rules.repository.api.FolderRepository;
import org.openl.rules.repository.api.Listener;
import org.openl.rules.repository.api.MergeConflictException;
import org.openl.rules.repository.api.RepositorySettings;
import org.openl.rules.repository.common.ChangesMonitor;
import org.openl.rules.repository.common.RevisionGetter;
import org.openl.rules.repository.git.branch.BranchDescription;
import org.openl.rules.repository.git.branch.BranchesData;
import org.openl.util.FileUtils;
import org.openl.util.IOUtils;
import org.openl.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

public class GitRepository implements FolderRepository, BranchRepository, Closeable {
    static final String DELETED_MARKER_FILE = ".archived";

    private final Logger log = LoggerFactory.getLogger(GitRepository.class);

    private String id;
    private String name;
    private String uri;
    private String login;
    private String password;
    private String userDisplayName;
    private String userEmail;
    private String localRepositoryPath;
    private String branch = Constants.MASTER;
    private String baseBranch = branch;
    private String tagPrefix = StringUtils.EMPTY;
    private int listenerTimerPeriod = 10;
    private int connectionTimeout = 60;
    private String commentTemplate;
    private String escapedCommentTemplate;
    private CommitMessageParser commitMessageParser;
    private RepositorySettings repositorySettings;
    private Date settingsSyncDate = new Date();
    private boolean noVerify;
    private Boolean gcAutoDetach;
    private int failedAuthenticationSeconds;
    private Integer maxAuthenticationAttempts;

    private ChangesMonitor monitor;
    private Git git;
    private NotResettableCredentialsProvider credentialsProvider;

    private ReadWriteLock repositoryLock = new ReentrantReadWriteLock();
    private ReentrantLock remoteRepoLock = new ReentrantLock();

    private BranchesData branches = new BranchesData();

    private boolean closed;
    private String branchesConfigFile = "design/branches.yaml";

    public void setId(String id) {
        this.id = id;
        if (id != null) {
            branchesConfigFile = id + "/branches.yaml";
        }
    }

    @Override
    public String getId() {
        return id;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getBaseBranch() {
        return baseBranch;
    }

    @Override
    public List<FileData> list(String path) throws IOException {
        if (isEmpty()) {
            return Collections.emptyList();
        }
        ObjectId objectId = resolveBranchId();
        if (objectId == null) {
            return Collections.emptyList();
        }
        return iterate(path, new ListCommand(objectId));
    }

    @Override
    public FileData check(String name) throws IOException {
        return iterate(name, new CheckCommand());
    }

    @Override
    public FileItem read(String name) throws IOException {
        return iterate(name, new ReadCommand());
    }

    @Override
    @SuppressWarnings("squid:S2095") // resources are closed by IOUtils
    public FileData save(FileData data, InputStream stream) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("save(data, stream): lock");
            writeLock.lock();

            saveSingleFile(data, stream);
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(data, stream): unlock");
        }

        monitor.fireOnChange();

        return check(data.getName());
    }

    @Override
    public List<FileData> save(List<FileItem> fileItems) throws IOException {
        List<FileData> result = new ArrayList<>();
        Lock writeLock = repositoryLock.writeLock();
        String firstCommitId = null;
        try {
            log.debug("save(multipleFiles): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            for (FileItem fileItem : fileItems) {
                RevCommit commit = createCommit(fileItem.getData(), fileItem.getStream());
                if (firstCommitId == null) {
                    firstCommitId = commit.getId().getName();
                }

                resolveAndMerge(fileItem.getData(), false, commit);
                addTagToCommit(commit, firstCommitId, fileItem.getData().getAuthor());
            }
            push();
        } catch (IOException e) {
            reset(firstCommitId);
            throw e;
        } catch (Exception e) {
            reset(firstCommitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(multipleFiles): unlock");
        }
        monitor.fireOnChange();

        for (FileItem fileItem : fileItems) {
            result.add(check(fileItem.getData().getName()));
        }
        return result;
    }

    private void saveSingleFile(FileData data, InputStream stream) throws IOException {
        String commitId = null;
        try {
            String parentVersion = data.getVersion();
            boolean checkoutOldVersion = isCheckoutOldVersion(data.getName(), parentVersion);
            checkoutForcedOrReset(checkoutOldVersion ? parentVersion : branch);
            RevCommit commit = createCommit(data, stream);
            commitId = commit.getId().getName();

            resolveAndMerge(data, checkoutOldVersion, commit);
            addTagToCommit(commit, data.getAuthor());

            push();
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        }
    }

    private RevCommit createCommit(FileData data, InputStream stream) throws GitAPIException, IOException {
        String fileInRepository = data.getName();

        File file = new File(localRepositoryPath, fileInRepository);
        createParent(file);
        IOUtils.copyAndClose(stream, new FileOutputStream(file));

        git.add().addFilepattern(fileInRepository).call();
        return git.commit()
            .setMessage(formatComment(CommitType.SAVE, data))
            .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                userEmail != null ? userEmail : "")
            .setOnly(fileInRepository)
            .setNoVerify(noVerify)
            .call();
    }

    @Override
    public boolean delete(FileData data) throws IOException {
        if (deleteInternal(data)) {
            monitor.fireOnChange();
            return true;
        }
        return false;
    }

    private boolean deleteInternal(FileData data) throws IOException {
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("delete(): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            String name = data.getName();
            File file = new File(localRepositoryPath, name);
            if (!file.exists()) {
                return false;
            }

            if (file.isDirectory()) {
                String commitMessage = formatComment(CommitType.ARCHIVE, data);

                // Create marker file if it absents and write current time
                try (DataOutputStream os = new DataOutputStream(
                    new FileOutputStream(new File(file, DELETED_MARKER_FILE)))) {
                    os.writeLong(System.currentTimeMillis());
                }

                String markerFile = name + "/" + DELETED_MARKER_FILE;
                git.add().addFilepattern(markerFile).call();
                RevCommit commit = git.commit()
                    .setMessage(commitMessage)
                    .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                        userEmail != null ? userEmail : "")
                    .setOnly(markerFile)
                    .setNoVerify(noVerify)
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit, data.getAuthor());
            } else {
                // Files cannot be archived. Only folders.
                git.rm().addFilepattern(name).call();
                RevCommit commit = git.commit()
                    .setMessage(formatComment(CommitType.ERASE, data))
                    .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                        userEmail != null ? userEmail : "")
                    .setNoVerify(noVerify)
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit, data.getAuthor());
            }

            push();

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            reset(commitId);
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("delete(): unlock");
        }

        return true;
    }

    @Override
    public boolean delete(List<FileData> data) throws IOException {
        boolean deleted = false;
        for (FileData f : data) {
            deleted |= deleteInternal(f);
        }
        if (deleted) {
            monitor.fireOnChange();
        }
        return deleted;
    }

    @SuppressWarnings("squid:S2095") // resources are closed by IOUtils
    private FileData copy(String srcName, FileData destData) throws IOException {
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("copy(): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            File src = new File(localRepositoryPath, srcName);
            File dest = new File(localRepositoryPath, destData.getName());
            IOUtils.copyAndClose(new FileInputStream(src), new FileOutputStream(dest));

            git.add().addFilepattern(destData.getName()).call();
            RevCommit commit = git.commit()
                .setMessage(formatComment(CommitType.SAVE, destData))
                .setCommitter(userDisplayName != null ? userDisplayName : destData.getAuthor(),
                    userEmail != null ? userEmail : "")
                .setNoVerify(noVerify)
                .call();
            commitId = commit.getId().getName();

            addTagToCommit(commit, destData.getAuthor());

            push();
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e);
        } finally {
            writeLock.unlock();
            log.debug("copy(): unlock");
        }

        monitor.fireOnChange();

        return check(destData.getName());
    }

    @Override
    public void setListener(Listener callback) {
        if (monitor != null) {
            monitor.setListener(callback);
        }
    }

    @Override
    public List<FileData> listHistory(String name) throws IOException {
        return iterateHistory(name, new ListHistoryVisitor());
    }

    @Override
    public List<FileData> listFiles(String path, String version) throws IOException {
        return parseHistory(path, version, new ListFilesHistoryVisitor(version));
    }

    @Override
    public FileData checkHistory(String name, String version) throws IOException {
        return parseHistory(name, version, new CheckHistoryVisitor(version));
    }

    @Override
    public FileItem readHistory(String name, String version) throws IOException {
        return parseHistory(name, version, new ReadHistoryVisitor(version));
    }

    @Override
    public boolean deleteHistory(FileData data) throws IOException {
        String name = data.getName();
        String version = data.getVersion();
        String author = StringUtils.trimToEmpty(data.getAuthor());
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("deleteHistory(): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            RevCommit commit;
            if (version == null) {
                git.rm().addFilepattern(name).call();
                String commitMessage = formatComment(CommitType.ERASE, data);
                commit = git.commit()
                    .setCommitter(userDisplayName != null ? userDisplayName : author,
                        userEmail != null ? userEmail : "")
                    .setMessage(commitMessage)
                    .setOnly(name)
                    .setNoVerify(noVerify)
                    .call();
            } else {
                FileData fileData = checkHistory(name, version);
                if (fileData == null) {
                    return false;
                }

                if (!fileData.isDeleted()) {
                    // We can "delete" only archived versions. Other version cannot be deleted.
                    return false;
                }

                String markerFile = name + "/" + DELETED_MARKER_FILE;
                git.rm().addFilepattern(markerFile).call();
                String commitMessage = formatComment(CommitType.RESTORE, data);
                commit = git.commit()
                    .setCommitter(userDisplayName != null ? userDisplayName : author,
                        userEmail != null ? userEmail : "")
                    .setMessage(commitMessage)
                    .setOnly(markerFile)
                    .setNoVerify(noVerify)
                    .call();
            }

            commitId = commit.getId().getName();
            addTagToCommit(commit, author);

            push();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            reset(commitId);
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("deleteHistory(): unlock");
        }

        monitor.fireOnChange();
        return true;
    }

    @Override
    public FileData copyHistory(String srcName, FileData destData, String version) throws IOException {
        if (version == null) {
            return copy(srcName, destData);
        }

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("copyHistory(): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            File src = new File(localRepositoryPath, srcName);
            if (src.isDirectory()) {
                List<FileItem> files = new ArrayList<>();
                try {
                    List<FileData> fileData = listFiles(srcName + "/", version);
                    for (FileData data : fileData) {
                        String fileFrom = data.getName();
                        FileItem fileItem = readHistory(fileFrom, data.getVersion());
                        String fileTo = destData.getName() + fileFrom.substring(srcName.length());
                        files.add(new FileItem(fileTo, fileItem.getStream()));
                    }
                    saveMultipleFiles(destData, files, ChangesetType.FULL);
                } finally {
                    for (FileItem file : files) {
                        IOUtils.closeQuietly(file.getStream());
                    }
                }
            } else {
                FileItem fileItem = null;
                try {
                    fileItem = readHistory(srcName, version);

                    destData.setSize(fileItem.getData().getSize());

                    saveSingleFile(destData, fileItem.getStream());
                } finally {
                    if (fileItem != null) {
                        IOUtils.closeQuietly(fileItem.getStream());
                    }
                }
            }
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e);
        } finally {
            writeLock.unlock();
            log.debug("copyHistory(): unlock");
        }

        monitor.fireOnChange();
        return check(destData.getName());
    }

    public void initialize() {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("initialize(): lock");
            writeLock.lock();

            if (StringUtils.isNotBlank(login) && StringUtils.isNotBlank(password)) {
                credentialsProvider = new NotResettableCredentialsProvider(login,
                    password,
                    name,
                    failedAuthenticationSeconds,
                    maxAuthenticationAttempts);
            }

            File local = new File(localRepositoryPath);

            boolean shouldCloneOrInit;
            boolean shouldUpdateOrigin = false;
            if (!local.exists()) {
                shouldCloneOrInit = true;
            } else {
                File[] files = local.listFiles();
                if (files == null) {
                    throw new IOException(String.format("'%s' is not a directory.", local));
                }

                if (files.length > 0) {
                    if (RepositoryCache.FileKey.resolve(local, FS.DETECTED) != null) {
                        log.debug("Reuse existing git repository {}", local);
                        try (Repository repository = Git.open(local).getRepository()) {
                            if (uri != null) {
                                String remoteUrl = repository.getConfig()
                                    .getString(ConfigConstants.CONFIG_REMOTE_SECTION,
                                        Constants.DEFAULT_REMOTE_NAME,
                                        ConfigConstants.CONFIG_KEY_URL);
                                if (!uri.equals(remoteUrl)) {
                                    URI proposedUri = getUri(uri);
                                    URI savedUri = getUri(remoteUrl);
                                    if (!proposedUri.equals(savedUri)) {
                                        if (isSame(proposedUri, savedUri)) {
                                            shouldUpdateOrigin = true;
                                        } else {
                                            throw new IOException(String.format(
                                                    "Folder '%s' already contains local git repository, but is configured to different URI (%s).\nDelete it or choose another local path or set correct URL for repository.",
                                                    local,
                                                    remoteUrl));
                                        }
                                    }
                                }
                            }
                        }
                        shouldCloneOrInit = false;
                    } else {
                        // Cannot overwrite existing files that is definitely not git repository
                        throw new IOException(String.format(
                            "Folder '%s' already exists and is not a git repository. Use another local path or delete the existing folder to create a git repository.",
                            local));
                    }
                } else {
                    shouldCloneOrInit = true;
                }
            }

            if (shouldCloneOrInit) {
                try {
                    if (uri != null) {
                        CloneCommand cloneCommand = Git.cloneRepository()
                            .setURI(uri)
                            .setDirectory(local)
                            .setBranch(branch)
                            .setCloneAllBranches(true);

                        CredentialsProvider credentialsProvider = getCredentialsProvider(GitActionType.CLONE);
                        if (credentialsProvider != null) {
                            cloneCommand.setCredentialsProvider(credentialsProvider);
                        }

                        Git cloned = cloneCommand.call();
                        successAuthentication(GitActionType.CLONE);
                        cloned.close();
                    } else {
                        Git repo = Git.init().setDirectory(local).call();
                        repo.close();
                    }
                } catch (Exception e) {
                    FileUtils.deleteQuietly(local);
                    throw e;
                }
            } else if (shouldUpdateOrigin) {
                try (Repository repository = Git.open(local).getRepository()) {
                    StoredConfig config = repository.getConfig();
                    config.setString(ConfigConstants.CONFIG_REMOTE_SECTION,
                                    Constants.DEFAULT_REMOTE_NAME,
                                    ConfigConstants.CONFIG_KEY_URL,
                                    uri);
                    config.save();
                }
            }

            git = Git.open(local);
            StoredConfig config = git.getRepository().getConfig();
            if (StringUtils.isNotBlank(userDisplayName)) {
                config.setString(ConfigConstants.CONFIG_USER_SECTION,
                    null,
                    ConfigConstants.CONFIG_KEY_NAME,
                    userDisplayName);
            } else {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME);
            }
            if (StringUtils.isNotBlank(userEmail)) {
                config
                    .setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, userEmail);
            } else {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL);
            }

            if (gcAutoDetach != null) {
                config.setBoolean(ConfigConstants.CONFIG_GC_SECTION,
                    null,
                    ConfigConstants.CONFIG_KEY_AUTODETACH,
                    gcAutoDetach);
            }

            config.save();

            // Track all remote branches as local branches
            List<Ref> remoteBranches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            TreeSet<String> localBranches = getAvailableBranches();
            String remotePrefix = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/";
            for (Ref remoteBranch : remoteBranches) {
                if (remoteBranch.isSymbolic()) {
                    log.debug("Skip the symbolic branch '{}'.", remoteBranch.getName());
                    continue;
                }
                if (!remoteBranch.getName().startsWith(remotePrefix)) {
                    log.warn("The branch {} will not be tracked", remoteBranch.getName());
                    continue;
                }
                String branchName = remoteBranch.getName().substring(remotePrefix.length());
                try {
                    if (!localBranches.contains(branchName)) {
                        createRemoteTrackingBranch(branchName);
                    }
                } catch (RefAlreadyExistsException e) {
                    // the error may appear on non-case sensitive OS
                    log.warn(
                        "The branch '{}' will not be tracked because a branch with the same name already exists. Branches with the same name, but different capitalization do not work on non-case sensitive OS.",
                        remoteBranch.getName());
                }
            }

            // Check if we should skip hooks.
            noVerify = false;
            File hookDir = new File(git.getRepository().getDirectory(), Constants.HOOKS);
            File preCommitHook = new File(hookDir, PreCommitHook.NAME);
            File commitMsgHook = new File(hookDir, CommitMsgHook.NAME);
            if (!preCommitHook.isFile() && !commitMsgHook.isFile()) {
                log.debug("Hooks are absent");
                noVerify = true;
            } else {
                try {
                    if (!Files.isExecutable(preCommitHook.toPath()) || !Files.isExecutable(commitMsgHook.toPath())) {
                        log.debug("Hook exists but not executable");
                        noVerify = true;
                    }
                } catch (SecurityException e) {
                    log.warn("Hook exists but there is no access to invoke the file.", e);
                    noVerify = true;
                }
            }

            if (!shouldCloneOrInit && uri != null) {
                try {
                    FetchResult fetchResult = fetchAll();
                    doFastForward(fetchResult);
                    fastForwardNotMergedCommits(fetchResult);
                } catch (Exception e) {
                    log.warn(e.getMessage(), e);
                    if (credentialsProvider == null) {
                        String message = e.getMessage();
                        if (message != null && message.contains(JGitText.get().noCredentialsProvider)) {
                            throw new IOException(
                                "Authentication is required but login and password has not been specified.");
                        }
                    } else if (credentialsProvider.isHasAuthorizationFailure()) {
                        throw new IOException("Incorrect login or password.");
                    }
                    // For other cases like temporary connection loss we should not fail. The local repository exists,
                    // will fetch later.
                }
            }

            lockSettings();
            try {
                readBranches();
            } finally {
                unlockSettings();
            }
            if (credentialsProvider != null) {
                credentialsProvider.successAuthentication(GitActionType.INIT);
            }
            monitor = new ChangesMonitor(new GitRevisionGetter(), listenerTimerPeriod);
        } catch (Exception e) {
            Throwable cause = ExceptionUtils.getRootCause(e);
            if (cause == null) {
                cause = e;
            }

            // Unknown host
            if (cause instanceof UnknownHostException) {
                String error = "Unknown host for URL " + uri;
                final String message = cause.getMessage();
                if (message != null) {
                    error += " Root cause message: " + message;
                }
                throw new IllegalArgumentException(error, e);
            }

            // Other cases
            throw new IllegalStateException("Failed to initialize a repository", e);
        } finally {
            writeLock.unlock();
            log.debug("initialize(): unlock");
        }
    }

    @Override
    public void close() {
        closed = true;

        if (monitor != null) {
            monitor.release();
            monitor = null;
        }
        if (git != null) {
            git.close();
            git = null;
        }
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setUserDisplayName(String userDisplayName) {
        this.userDisplayName = userDisplayName;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public void setLocalRepositoryPath(String localRepositoryPath) {
        this.localRepositoryPath = localRepositoryPath;
    }

    public void setBranch(String branch) {
        this.branch = StringUtils.isBlank(branch) ? Constants.MASTER : branch;
        this.baseBranch = this.branch;
    }

    public void setTagPrefix(String tagPrefix) {
        this.tagPrefix = StringUtils.trimToEmpty(tagPrefix);
    }

    public void setListenerTimerPeriod(int listenerTimerPeriod) {
        this.listenerTimerPeriod = listenerTimerPeriod;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public void setFailedAuthenticationSeconds(int failedAuthenticationSeconds) {
        this.failedAuthenticationSeconds = failedAuthenticationSeconds;
    }

    public void setMaxAuthenticationAttempts(Integer maxAuthenticationAttempts) {
        this.maxAuthenticationAttempts = maxAuthenticationAttempts;
    }

    public void setCommentTemplate(String commentTemplate) {
        this.commentTemplate = commentTemplate;
        String ct = commentTemplate.replace("{commit-type}", "{0}")
            .replace("{user-message}", "{1}")
            .replace("{username}", "{2}");
        this.escapedCommentTemplate = escapeCurlyBrackets(ct);
        this.commitMessageParser = new CommitMessageParser(commentTemplate);
    }

    public void setRepositorySettings(RepositorySettings repositorySettings) {
        this.repositorySettings = repositorySettings;
    }

    /**
     * If null, don't modify "gc.autoDetach" state. Otherwise, save it as a git repository setting.
     */
    public void setGcAutoDetach(Boolean gcAutoDetach) {
        this.gcAutoDetach = gcAutoDetach;
    }

    private static TreeWalk buildTreeWalk(org.eclipse.jgit.lib.Repository repository,
            String path,
            RevTree tree) throws IOException {
        TreeWalk treeWalk;
        if (StringUtils.isEmpty(path)) {
            treeWalk = new TreeWalk(repository);
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);
            treeWalk.setPostOrderTraversal(false);
        } else {
            treeWalk = TreeWalk.forPath(repository, path, tree);
        }

        if (treeWalk == null) {
            throw new FileNotFoundException(
                String.format("Missed expected path '%s' in tree '%s'.", path, tree.getName()));
        }
        return treeWalk;
    }

    private FileData createFileData(TreeWalk dirWalk, String baseFolder, ObjectId start) {
        String fullPath = baseFolder + dirWalk.getPathString();
        return new LazyFileData(branch, fullPath, this, start, getFileId(dirWalk), commitMessageParser);
    }

    private boolean isEmpty() throws IOException {
        Ref headRef = git.getRepository().exactRef(Constants.HEAD);
        return headRef == null || headRef.getObjectId() == null;
    }

    private ObjectId resolveBranchId() throws IOException {
        if (git.getRepository().findRef(branch) != null) {
            return git.getRepository().resolve(branch);
        }
        return null;
    }

    private FileData createFileData(TreeWalk dirWalk, RevCommit fileCommit) {
        String fullPath = dirWalk.getPathString();

        return new LazyFileData(branch, fullPath, this, fileCommit, getFileId(dirWalk), commitMessageParser);
    }

    private ObjectId getFileId(TreeWalk dirWalk) {
        int fileModeBits = dirWalk.getFileMode().getBits();
        ObjectId fileId = null;
        if ((fileModeBits & FileMode.TYPE_FILE) != 0) {
            fileId = dirWalk.getObjectId(0);
        }
        return fileId;
    }

    ObjectId getLastRevision() throws GitAPIException, IOException {
        FetchResult fetchResult = null;

        Lock readLock = repositoryLock.readLock();

        if (uri != null) {
            try {
                readLock.lock();

                boolean remoteLocked = remoteRepoLock.tryLock();
                if (!remoteLocked) {
                    // Skip because is already fetching by other thread.
                    return null;
                }
                try {
                    fetchResult = fetchAll();
                } finally {
                    remoteRepoLock.unlock();
                }
            } finally {
                readLock.unlock();
            }
        }

        boolean branchesChanged = false;
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("getLastRevision(): lock write");
            writeLock.lock();

            if (fetchResult != null) {
                branchesChanged = doFastForward(fetchResult);
                fastForwardNotMergedCommits(fetchResult);
            }

            TreeSet<String> availableBranches = getAvailableBranches();

            BranchesData branches = getBranches(true);
            Set<String> projectBranches = branches.getDescriptions()
                .stream()
                .map(BranchDescription::getName)
                .collect(Collectors.toCollection(HashSet::new));
            branches.getProjectBranches().values().forEach(projectBranches::addAll);

            List<String> branchesToRemove = new ArrayList<>();
            projectBranches.forEach(projectBranch -> {
                if (!availableBranches.contains(projectBranch)) {
                    branchesToRemove.add(projectBranch);
                }
            });

            if (!branchesToRemove.isEmpty()) {
                lockSettings();
                try {
                    for (String projectBranch : branchesToRemove) {
                        branches.removeBranch(null, projectBranch);
                    }
                    saveBranches();
                } finally {
                    unlockSettings();
                }
            }
        } finally {
            writeLock.unlock();
            log.debug("getLastRevision(): unlock write");
        }

        if (branchesChanged) {
            monitor.fireOnChange();
        }

        try {
            log.debug("getLastRevision(): lock");
            readLock.lock();
            return git.getRepository().resolve("HEAD^{tree}");
        } finally {
            readLock.unlock();
            log.debug("getLastRevision(): unlock");
        }
    }

    private BranchesData getBranches(boolean withLock) throws IOException {
        if (repositorySettings != null) {
            boolean modified = !repositorySettings.getSyncDate().equals(settingsSyncDate);
            if (!modified) {
                FileData fileData = repositorySettings.getRepository().check(branchesConfigFile);
                modified = fileData != null && settingsSyncDate.before(fileData.getModifiedAt());
            }
            if (modified) {
                if (withLock) {
                    lockSettings();
                    try {
                        readBranches();
                    } finally {
                        unlockSettings();
                    }
                } else {
                    readBranches();
                }
            }
        }
        return branches;
    }

    /**
     * @return true if need to force listener invocation. It can be if some branch was added or deleted.
     */
    private boolean doFastForward(FetchResult fetchResult) throws GitAPIException, IOException {
        boolean branchesChanged = false;
        for (TrackingRefUpdate refUpdate : fetchResult.getTrackingRefUpdates()) {
            RefUpdate.Result result = refUpdate.getResult();
            switch (result) {
                case FAST_FORWARD:
                    if (!isEmpty()) {
                        checkoutForced(refUpdate.getRemoteName());
                    }

                    // It's assumed that we don't have unpushed commits at this point so there must be no additional
                    // merge
                    // while checking last revision. Accept only fast forwards.
                    git.merge()
                        .include(refUpdate.getNewObjectId())
                        .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                        .call();
                    break;
                case REJECTED_CURRENT_BRANCH:
                    checkoutForced(baseBranch); // On the next fetch the branch probably will be deleted
                    break;
                case FORCED:
                    if (ObjectId.zeroId().equals(refUpdate.getNewObjectId())) {
                        String remoteName = refUpdate.getRemoteName();
                        if (remoteName.startsWith(Constants.R_HEADS)) {
                            // Delete the branch
                            String branchToDelete = Repository.shortenRefName(remoteName);
                            String currentBranch = Repository.shortenRefName(git.getRepository().getFullBranch());
                            if (branchToDelete.equals(currentBranch)) {
                                String branchToCheckout = git.lsRemote()
                                    .callAsMap()
                                    .get("HEAD")
                                    .getObjectId()
                                    .getName();
                                checkoutForced(branchToCheckout);
                            }
                            git.branchDelete().setBranchNames(branchToDelete).setForce(true).call();
                            branchesChanged = true;
                        }
                    }
                    break;
                case NEW:
                    if (ObjectId.zeroId().equals(refUpdate.getOldObjectId())) {
                        String remoteName = refUpdate.getRemoteName();
                        if (remoteName.startsWith(Constants.R_HEADS)) {
                            createRemoteTrackingBranch(Repository.shortenRefName(remoteName));
                            branchesChanged = true;
                        }
                    }
                    break;
                case NO_CHANGE:
                    // Do nothing
                    break;
                default:
                    log.warn("Unsupported type of fetch result type: {}", result);
                    break;
            }
        }

        return branchesChanged;
    }

    private void fastForwardNotMergedCommits(FetchResult fetchResult) throws IOException, GitAPIException {
        // Support the case when for some reason commits were fetched but not merged to current branch earlier.
        // In this case fetchResult.getTrackingRefUpdates() can be empty.
        // If everything is merged into current branch, this method does nothing.
        // Obviously this method is not needed. It's invoked only to fix unexpected errors during work with repository.
        Ref advertisedRef = fetchResult.getAdvertisedRef(Constants.R_HEADS + branch);
        Ref localRef = git.getRepository().findRef(branch);
        if (localRef != null && advertisedRef != null && !localRef.getObjectId().equals(advertisedRef.getObjectId())) {
            if (isMergedInto(advertisedRef.getObjectId(), localRef.getObjectId(), false)) {
                // We enter here only if we have inconsistent repository state. Need additional investigation if this
                // occurred. For example this can happen if we discarded locally some commit but the branch didn't move
                // to the desired new HEAD.
                log.warn(
                    "Advertised commit is already merged into current head in branch '{}'. Current HEAD: {}, advertised ref: {}",
                    branch,
                    localRef.getObjectId().name(),
                    advertisedRef.getObjectId().name());
            } else {
                // Typically this shouldn't occur. But if found such case, should fast-forward local repository and
                // write
                // warning for future investigation.
                log.warn(
                    "Found commits that are not fast forwarded in branch '{}'. Current HEAD: {}, advertised ref: {}",
                    branch,
                    localRef.getObjectId().name(),
                    advertisedRef.getObjectId().name());
                checkoutForced(branch);
                git.merge().include(advertisedRef).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call();
            }
        }
    }

    private void pull(String commitToRevert, String mergeAuthor) throws GitAPIException, IOException {
        if (uri == null) {
            return;
        }

        FetchResult fetchResult;
        try {
            remoteRepoLock.lock();
            fetchResult = fetchAll();
        } finally {
            remoteRepoLock.unlock();
        }

        try {
            Ref r = fetchResult.getAdvertisedRef(branch);
            if (r == null) {
                r = fetchResult.getAdvertisedRef(Constants.R_HEADS + branch);
            }
            if (r == null) {
                r = git.getRepository().findRef(branch);
            }

            if (r == null) {
                return;
            }

            String mergeMessage = getMergeMessage(mergeAuthor, r);

            MergeResult mergeResult = git.merge()
                .include(r.getObjectId())
                .setStrategy(MergeStrategy.RECURSIVE)
                .setMessage(mergeMessage)
                .call();

            if (!mergeResult.getMergeStatus().isSuccessful()) {
                validateMergeConflict(mergeResult, true);
                throw new IOException("Cannot merge: " + mergeResult.toString());
            }
        } catch (GitAPIException | IOException e) {
            reset(commitToRevert);
            throw e;
        } finally {
            try {
                doFastForward(fetchResult);
            } catch (Exception e) {
                // Don't override exception thrown in catch block.
                log.error(e.getMessage(), e);
            }
        }
    }

    private void validateMergeConflict(MergeResult mergeResult, boolean theirToOur) throws GitAPIException,
                                                                                    IOException {
        if (mergeResult != null && mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
            ObjectId[] mergedCommits = mergeResult.getMergedCommits();
            Repository repository = git.getRepository();
            List<Ref> tags = git.tagList().call();

            String baseCommit = getVersionName(repository, tags, mergeResult.getBase());

            String ourCommit = null;
            String theirCommit = null;
            ObjectId ourId = null;
            ObjectId theirId = null;

            if (mergedCommits.length > 0) {
                String commit = getVersionName(repository, tags, mergedCommits[0]);
                if (theirToOur) {
                    ourId = mergedCommits[0];
                    ourCommit = commit;
                } else {
                    theirId = mergedCommits[0];
                    theirCommit = commit;
                }
            }
            if (mergedCommits.length > 1) {
                String commit = getVersionName(repository, tags, mergedCommits[1]);
                if (theirToOur) {
                    theirId = mergedCommits[1];
                    theirCommit = commit;
                } else {
                    ourId = mergedCommits[1];
                    ourCommit = commit;
                }
            }

            Set<String> conflictedFiles = mergeResult.getConflicts().keySet();
            Map<String, String> diffs = new HashMap<>();

            if (ourId != null && theirId != null) {
                AbstractTreeIterator ourTreeParser = prepareTreeParser(repository, ourId);
                AbstractTreeIterator theirTreeParser = prepareTreeParser(repository, theirId);

                List<DiffEntry> diff = git.diff()
                    .setOldTree(theirTreeParser)
                    .setNewTree(ourTreeParser)
                    .setPathFilter(PathFilterGroup.createFromStrings(conflictedFiles))
                    .call();

                Pattern oldPathPattern = Pattern.compile("(diff --git .+\\n.+--- \"a/).+?(\".*\\n@@.+)", Pattern.DOTALL);
                Pattern newPathPattern = Pattern.compile("(diff --git .+\\n.+\\+\\+\\+ \"b/).+?(\".*\\n@@.+)", Pattern.DOTALL);
                for (DiffEntry entry : diff) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    try (DiffFormatter formatter = new DiffFormatter(outputStream)) {
                        formatter.setRepository(repository);
                        formatter.setQuotePaths(false);
                        formatter.format(entry);
                        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath()
                                                                                           : entry.getNewPath();
                        String comparison = outputStream.toString(StandardCharsets.UTF_8.name());

                        // JGit currently doesn't support switching off quoting symbols with code < 0x80, so we used
                        // decode paths ourselves.
                        Matcher oldPathMatcher = oldPathPattern.matcher(comparison);
                        if (oldPathMatcher.matches()) {
                            comparison = oldPathMatcher.replaceFirst("$1" + entry.getOldPath() + "$2");
                        }
                        Matcher newPathMatcher = newPathPattern.matcher(comparison);
                        if (newPathMatcher.matches()) {
                            comparison = newPathMatcher.replaceFirst("$1" + entry.getNewPath() + "$2");
                        }
                        diffs.put(path, comparison);
                    }
                }
            }

            throw new MergeConflictException(diffs, baseCommit, ourCommit, theirCommit);
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        // noinspection Duplicates
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }

            walk.dispose();

            return treeParser;
        }
    }

    private FetchResult fetchAll() throws GitAPIException {
        FetchCommand fetchCommand = git.fetch();
        CredentialsProvider credentialsProvider = getCredentialsProvider(GitActionType.FETCH_ALL);
        if (credentialsProvider != null) {
            fetchCommand.setCredentialsProvider(credentialsProvider);
        }
        fetchCommand.setRefSpecs(new RefSpec().setSourceDestination(Constants.R_HEADS + "*",
            Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/*"));
        fetchCommand.setRemoveDeletedRefs(true);
        fetchCommand.setTimeout(connectionTimeout);
        FetchResult result = fetchCommand.call();
        successAuthentication(GitActionType.FETCH_ALL);
        return result;
    }

    private void push() throws GitAPIException, IOException {
        if (uri == null) {
            return;
        }

        try {
            remoteRepoLock.lock();
            PushCommand push;

            if (git.getRepository().findRef(branch) != null) {
                push = git.push().setPushTags().add(branch).setTimeout(connectionTimeout);
            } else if (git.getRepository().findRef(baseBranch) == null) {
                git.getRepository().updateRef(baseBranch);
                git.branchCreate().setName(baseBranch).setForce(true).call();
                push = git.push().setPushTags().add(baseBranch).setTimeout(connectionTimeout);
            } else {
                throw new IOException(String.format("Cannot find branch '%s'", branch));
            }

            CredentialsProvider credentialsProvider = getCredentialsProvider(GitActionType.PUSH);
            if (credentialsProvider != null) {
                push.setCredentialsProvider(credentialsProvider);
            }

            Iterable<PushResult> results = push.call();
            successAuthentication(GitActionType.PUSH);
            validatePushResults(results);
        } finally {
            remoteRepoLock.unlock();
        }
    }

    private void validatePushResults(Iterable<PushResult> results) throws IOException {
        for (PushResult result : results) {
            log.debug(result.getMessages());

            Collection<RemoteRefUpdate> remoteUpdates = result.getRemoteUpdates();
            for (RemoteRefUpdate remoteUpdate : remoteUpdates) {
                RemoteRefUpdate.Status status = remoteUpdate.getStatus();
                switch (status) {
                    case OK:
                    case UP_TO_DATE:
                    case NON_EXISTING:
                        // Successful operation. Continue.
                        break;
                    case REJECTED_NONFASTFORWARD:
                        throw new IOException(
                            "Remote ref update was rejected, as it would cause non fast-forward update.");
                    case REJECTED_NODELETE:
                        throw new IOException(
                            "Remote ref update was rejected, because remote side does not support/allow deleting refs.");
                    case REJECTED_REMOTE_CHANGED:
                        throw new IOException(
                            "Remote ref update was rejected, because old object id on remote repository wasn't the same as defined expected old object.");
                    case REJECTED_OTHER_REASON:
                        String message = remoteUpdate.getMessage();
                        if ("pre-receive hook declined".equals(message)) {
                            message = "Remote git server rejected your commit because of pre-receive hook. Details:\n" + result
                                .getMessages();
                        }
                        throw new IOException(message);
                    case AWAITING_REPORT:
                        throw new IOException(
                            "Push process is awaiting update report from remote repository. This is a temporary state or state after critical error in push process.");
                    default:
                        throw new IOException(
                            "Push process returned with status " + status + " and message " + remoteUpdate
                                .getMessage());
                }
            }
        }
    }

    private <T> T iterate(String path, WalkCommand<T> command) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("iterate(): lock");
            readLock.lock();

            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            if (isEmpty()) {
                return command.apply(repository, null, path);
            }

            try (RevWalk walk = new RevWalk(repository)) {
                ObjectId branchId = resolveBranchId();
                if (branchId == null) {
                    return command.apply(repository, null, path);
                }
                RevCommit commit = walk.parseCommit(branchId);
                RevTree tree = commit.getTree();

                // Create TreeWalk for root folder
                try (TreeWalk rootWalk = buildTreeWalk(repository, path, tree)) {
                    return command.apply(repository, rootWalk, path);
                } catch (FileNotFoundException e) {
                    return command.apply(repository, null, path);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("iterate(): unlock");
        }
    }

    private <T> T iterateHistory(String name, HistoryVisitor<T> historyVisitor) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("iterateHistory(): lock");
            readLock.lock();

            if (isEmpty()) {
                return historyVisitor.getResult();
            }

            // We can't use git.log().addPath(path) because jgit has some issues for some scenarios when merging commits
            // so some history elements aren't shown. So we iterate all commits and filter them out ourselves.
            Iterator<RevCommit> iterator = git.log().add(resolveBranchId()).call().iterator();

            List<Ref> tags = git.tagList().call();

            Repository repository = git.getRepository();

            try (ObjectReader or = repository.newObjectReader()) {
                TreeWalk tw = createTreeWalk(or, name);

                while (iterator.hasNext()) {
                    RevCommit commit = iterator.next();
                    if (!hasChangesInPath(tw, commit)) {
                        continue;
                    }

                    boolean stop = historyVisitor.visit(name, commit, getVersionName(repository, tags, commit));
                    if (stop) {
                        break;
                    }
                }
            }

            return historyVisitor.getResult();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("iterateHistory(): unlock");
        }
    }

    private <T> T parseHistory(String name, String version, HistoryVisitor<T> historyVisitor) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("parseHistory(): lock");
            readLock.lock();

            List<Ref> tags = git.tagList().call();

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                if (isEmpty()) {
                    return historyVisitor.getResult();
                }

                ObjectId id = getCommitByVersion(version);
                if (id != null) {
                    RevCommit commit = walk.parseCommit(id);
                    historyVisitor.visit(name, commit, getVersionName(git.getRepository(), tags, commit));
                } else {
                    log.warn("Can't find commit for version {}", version);
                }
                return historyVisitor.getResult();
            }
        } catch (MissingObjectException e) {
            log.error(e.getMessage());
            return null;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("parseHistory(): unlock");
        }
    }

    /**
     * Reset work dir and index.
     */
    private void reset() {
        reset(null);
    }

    /**
     * Reset work dir, index and discard commit. if {@code commitToDiscard} is null, then just reset work dir and index.
     *
     * @param commitToDiscard if null, commit will not be discarded. If not null, commit with that id will be discarded.
     */
    private void reset(String commitToDiscard) {
        try {
            String fullBranch = git.getRepository().getFullBranch();
            if (ObjectId.isId(fullBranch)) {
                // Detached HEAD. Just checkout to current branch and reset working dir.
                log.debug("Found detached HEAD: {}.", fullBranch);
                git.checkout().setName(branch).setForced(true).call();
            } else {
                ResetCommand resetCommand = git.reset().setMode(ResetCommand.ResetType.HARD);
                // If commit is not merged to our branch, it's detached - in this case no need to reset commit tree.
                if (commitToDiscard != null && isCommitMerged(commitToDiscard)) {
                    log.debug("Discard commit: {}.", commitToDiscard);
                    resetCommand.setRef(commitToDiscard + "^");
                }
                try {
                    resetCommand.call();
                } catch (JGitInternalException e) {
                    // check if index file is corrupted
                    File indexFile = git.getRepository().getIndexFile();
                    try {
                        DirCache dc = new DirCache(indexFile, git.getRepository().getFS());
                        dc.read();
                        log.error(e.getMessage(), e);
                    } catch (CorruptObjectException ex) {
                        log.error("git index file is corrupted and will be deleted", e);
                        if (!indexFile.delete() && indexFile.exists()) {
                            log.warn("Cannot delete corrupted index file {}.", indexFile);
                        }
                        resetCommand.call();
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void resetToCommit(String refToResetTo) {
        try {
            if (refToResetTo != null) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(refToResetTo).call();
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void checkoutForcedOrReset(String branch) throws IOException, GitAPIException {
        if (isEmpty()) {
            reset();
        } else {
            checkoutForced(branch);
        }
    }

    private void checkoutForced(String branch) throws GitAPIException {
        git.checkout().setName(branch).setForced(true).call();
    }

    private boolean isCommitMerged(String commitId) throws IOException {
        Repository repository = git.getRepository();
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit branchHead = revWalk.parseCommit(repository.resolve(Constants.R_HEADS + branch));
            RevCommit otherHead = revWalk.parseCommit(repository.resolve(commitId));
            return revWalk.isMergedInto(otherHead, branchHead);
        }
    }

    private String getNextTagId() throws GitAPIException {
        List<Ref> call = git.tagList().call();
        long maxId = 0;
        for (Ref tagRef : call) {
            String name = getLocalTagName(tagRef);
            if (name.startsWith(tagPrefix)) {
                int num;
                try {
                    num = Integer.parseInt(name.substring(tagPrefix.length()));
                } catch (NumberFormatException e) {
                    log.debug("Tag '{}' is skipped because it does not contain version number", name);
                    continue;
                }
                if (num > maxId) {
                    maxId = num;
                }
            }
        }

        return String.valueOf(maxId + 1);
    }

    static String getVersionName(Repository repository, List<Ref> tags, ObjectId commitId) throws IOException {
        Ref tagRef = getTagRefForCommit(repository, tags, commitId);

        return tagRef != null ? getLocalTagName(tagRef) : commitId.getName();
    }

    static RevCommit findFirstCommit(Git git, ObjectId startCommit, String path) throws IOException, GitAPIException {
        // We can't use git.log().addPath(path) because jgit has some issues for some scenarios when merging commits so
        // some history elements aren't shown. So we iterate all commits and filter them out ourselves.
        Repository repository = git.getRepository();
        try (ObjectReader or = repository.newObjectReader()) {
            TreeWalk tw = createTreeWalk(or, path);
            for (RevCommit commit : git.log().add(startCommit).call()) {
                if (hasChangesInPath(tw, commit)) {
                    return commit;
                }
            }
        }

        return null;
    }

    private static boolean hasChangesInPath(TreeWalk tw, RevCommit commit) throws IOException {
        RevCommit[] parents = commit.getParents();
        int parentsNum = parents.length;

        ObjectId[] trees = new ObjectId[parentsNum + 1];
        for (int i = 0; i < parentsNum; i++) {
            trees[i] = parents[i].getTree();
        }
        // The last tree is a tree for inspecting commit.
        trees[parentsNum] = commit.getTree();
        tw.reset(trees);

        int[] changes = new int[parentsNum];

        while (tw.next()) {
            if (parentsNum == 0) {
                // Path is changed but there are no parents. It's a first commit.
                return true;
            }

            int currentMode = tw.getRawMode(parentsNum);
            for (int i = 0; i < parentsNum; i++) {
                int parentMode = tw.getRawMode(i);
                if (currentMode != parentMode || !tw.idEqual(i, parentsNum)) {
                    // Path configured in tw was changed
                    changes[i]++;
                }
            }
        }

        if (parentsNum == 0) {
            return false;
        } else if (parentsNum == 1) {
            return changes[0] > 0;
        } else {
            boolean allChanged = true;
            boolean anyChanged = false;

            for (int change : changes) {
                if (change == 0) {
                    allChanged = false;
                } else {
                    anyChanged = true;
                }
            }
            if (allChanged) {
                // Merge commit is modified comparing to both parents. Definitely we must show it in history.
                return true;
            }
            if (anyChanged) {
                // Merge commit is same as one of the parents for inspecting path.
                // It can be in two cases:
                // 1) it's a merge commit with overwriting changes of a user (ours or theirs).
                // 2) merge commit doesn't introduce anything related to our path (merged changes are for other
                // paths not related to the path interesting to us).
                String fullMessage = commit.getFullMessage();
                // We assume that if some of the parents contains a change, and message contains "conflict" word,
                // then probably someone overwrites other user's changes in our project. So we should show that
                // commit in history to show that overwrite step.
                //
                // Otherwise (if we don't contain "conflict" word), most probably that just means that their branch
                // doesn't contain changes from our branch so that Merge commit isn't interesting for us because it
                // doesn't introduce any change to our branch.
                return fullMessage.contains("conflict") || fullMessage.contains("Conflict");
            }
        }

        return false;
    }

    private static TreeWalk createTreeWalk(ObjectReader or, String path) {
        TreeFilter t = AndTreeFilter.create(PathFilterGroup.create(Collections.singleton(PathFilter.create(path))),
            TreeFilter.ANY_DIFF);
        TreeWalk tw = new TreeWalk(or);
        tw.setFilter(t);
        tw.setRecursive(t.shouldBeRecursive());
        return tw;
    }

    private static Ref getTagRefForCommit(Repository repository, List<Ref> tags, ObjectId commitId) throws IOException {
        Ref tagRefForCommit = null;
        for (Ref tagRef : tags) {
            ObjectId objectId = repository.getRefDatabase().peel(tagRef).getPeeledObjectId();
            if (objectId == null) {
                objectId = tagRef.getObjectId();
            }

            if (objectId.equals(commitId)) {
                tagRefForCommit = tagRef;
                break;
            }
        }
        return tagRefForCommit;
    }

    private static String getLocalTagName(Ref tagRef) {
        String name = tagRef.getName();
        return name.startsWith(Constants.R_TAGS) ? name.substring(Constants.R_TAGS.length()) : name;
    }

    private void addTagToCommit(RevCommit commit, String mergeAuthor) throws GitAPIException, IOException {
        addTagToCommit(commit, commit.getId().getName(), mergeAuthor);
    }

    private void addTagToCommit(RevCommit commit, String commitToRevert, String mergeAuthor) throws GitAPIException,
                                                                                             IOException {
        pull(commitToRevert, mergeAuthor);

        if (!tagPrefix.isEmpty()) {
            String tagName = tagPrefix + getNextTagId();
            git.tag().setObjectId(commit).setName(tagName).call();
        }
    }

    @Override
    public List<FileData> listFolders(String path) throws IOException {
        return iterate(path, new ListFoldersCommand());
    }

    @Override
    public FileData save(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("save(folderData, files, changesetType): lock");
            writeLock.lock();

            saveMultipleFiles(folderData, files, changesetType);
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(folderData, files, changesetType): unlock");
        }

        monitor.fireOnChange();
        return check(folderData.getName());
    }

    @Override
    public List<FileData> save(List<FolderItem> folderItems, ChangesetType changesetType) throws IOException {
        List<FileData> result = new ArrayList<>();
        Lock writeLock = repositoryLock.writeLock();
        String firstCommitId = null;
        try {
            log.debug("save(folderItems, changesetType): lock");
            writeLock.lock();
            checkoutForcedOrReset(branch);

            for (FolderItem folderItem : folderItems) {
                RevCommit commit = createCommit(folderItem.getData(), folderItem.getFiles(), changesetType);
                if (firstCommitId == null) {
                    firstCommitId = commit.getId().getName();
                }

                resolveAndMerge(folderItem.getData(), false, commit);
                addTagToCommit(commit, firstCommitId, folderItem.getData().getAuthor());
            }
            push();
        } catch (IOException e) {
            reset(firstCommitId);
            throw e;
        } catch (Exception e) {
            reset(firstCommitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(folderItems, changesetType): unlock");
        }
        monitor.fireOnChange();

        for (FolderItem folderItem : folderItems) {
            result.add(check(folderItem.getData().getName()));
        }
        return result;
    }

    @Override
    public void pull(String author) throws IOException {
        if (uri == null) {
            return;
        }

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("pull(author): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            pull(null, author);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("pull(author): unlock");
        }

    }

    @Override
    public void merge(String branchFrom, String author, ConflictResolveData conflictResolveData) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        String refToResetTo = null;
        try {
            log.debug("merge(): lock");
            writeLock.lock();

            checkoutForcedOrReset(branch);

            if (conflictResolveData == null) {
                pull(null, author);
            }
            refToResetTo = git.getRepository().findRef(branch).getObjectId().getName();

            Ref branchRef = git.getRepository().findRef(branchFrom);
            String mergeMessage = getMergeMessage(author, branchRef);
            MergeResult mergeResult = git.merge()
                .include(branchRef)
                .setMessage(mergeMessage)
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .call();

            if (conflictResolveData != null) {
                resolveConflict(mergeResult, conflictResolveData, author);
            } else {
                validateMergeConflict(mergeResult, true);
            }

            pull(null, author);
            push();
        } catch (IOException e) {
            resetToCommit(refToResetTo);
            throw e;
        } catch (Exception e) {
            resetToCommit(refToResetTo);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("merge(): unlock");
        }

        monitor.fireOnChange();
    }

    @Override
    public boolean isMergedInto(String from, String to) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("isMergedInto(): lock");
            readLock.lock();
            Repository repository = git.getRepository();
            return isMergedInto(repository.resolve(from), repository.resolve(to), true);
        } finally {
            readLock.unlock();
            log.debug("isMergedInto(): unlock");
        }
    }

    /**
     * Checks if commit with id {@code fromId} is merged to commit with id {@code toId}.
     *
     * @param fromId origin
     * @param toId destination
     * @param skipEmptyChanges If false, it works as in git: Determine if a commit is reachable from another commit. If
     *            true, commits with empty changes will be skipped. In the latter case if there are no valuable changes
     *            in other branch (for example only merge commits gotten from our branch), this method returns true. If
     *            you are interested in all unmerged commits (including empty ones), use "false".
     * @return true if commit {@code fromId} is merged to commit with id {@code toId} and false otherwise.
     * @throws IOException if any error is occurred during this method
     */
    private boolean isMergedInto(ObjectId fromId, ObjectId toId, boolean skipEmptyChanges) throws IOException {
        Repository repository = git.getRepository();

        try (RevWalk revWalk = new RevWalk(repository)) {
            if (fromId == null || toId == null) {
                return false;
            }
            RevCommit fromCommit = revWalk.parseCommit(fromId);
            RevCommit toCommit = revWalk.parseCommit(toId);
            boolean merged = revWalk.isMergedInto(fromCommit, toCommit);
            if (!merged && skipEmptyChanges) {
                if (fromCommit.getParentCount() == 2) {
                    // fromCommit is a merge commit
                    final RevCommit parent1 = fromCommit.getParent(0);
                    final RevCommit parent2 = fromCommit.getParent(1);

                    if (hasSameContent(parent1, fromCommit) || hasSameContent(parent2, fromCommit)) {
                        // Merge commit has same content as one of their parents
                        final boolean firstParentMerged = isMergedInto(parent1, toCommit, true);
                        final boolean secondParentMerged = isMergedInto(parent2, toCommit, true);
                        if (firstParentMerged && secondParentMerged) {
                            // If both parents are merged to our commit and one of the parents is same as child (merge
                            // commit), we can assume that the merge commit doesn't have any valuable updates.
                            // So we can assume that all valuable changes are merged.
                            return true;
                        }
                    }
                }
            }
            return merged;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private boolean hasSameContent(RevCommit commit1, RevCommit commit2) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            List<DiffEntry> diffEntries = diffFormatter.scan(commit1, commit2);
            if (diffEntries.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void saveMultipleFiles(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException {

        String commitId = null;
        try {
            String parentVersion = folderData.getVersion();
            boolean checkoutOldVersion = isCheckoutOldVersion(folderData.getName(), parentVersion);
            checkoutForcedOrReset(checkoutOldVersion ? parentVersion : branch);

            RevCommit commit = createCommit(folderData, files, changesetType);
            commitId = commit.getId().getName();

            resolveAndMerge(folderData, checkoutOldVersion, commit);
            addTagToCommit(commit, folderData.getAuthor());

            push();

            if (uri == null) {
                // GC is required in local mode. In remote mode autoGC() will be invoked on each fetch or merge.
                // autoGC() didn't solve the issue for local repository, so we use gc() instead.
                try {
                    git.gc().call();
                } catch (Exception e) {
                    log.warn(e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        }
    }

    private RevCommit createCommit(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException, GitAPIException {
        String relativeFolder = folderData.getName();

        List<String> changedFiles = new ArrayList<>();

        // Add new files and update existing ones
        List<File> savedFiles = new ArrayList<>();
        for (FileItem change : files) {
            File file = new File(localRepositoryPath, change.getData().getName());
            savedFiles.add(file);
            applyChangeInWorkspace(change, changedFiles);
        }

        if (changesetType == ChangesetType.FULL) {
            // Remove absent files
            String basePath = new File(localRepositoryPath).getAbsolutePath();
            File folder = new File(localRepositoryPath, relativeFolder);
            removeAbsentFiles(basePath, folder, savedFiles);
        }

        CommitCommand commitCommand = git.commit()
            .setNoVerify(noVerify)
            .setMessage(formatComment(CommitType.SAVE, folderData))
            .setCommitter(userDisplayName != null ? userDisplayName : folderData.getAuthor(),
                userEmail != null ? userEmail : "");

        return commitChangedFiles(commitCommand);
    }

    private void applyChangeInWorkspace(FileItem change, Collection<String> changedFiles) throws IOException,
                                                                                          GitAPIException {
        File file = new File(localRepositoryPath, change.getData().getName());
        createParent(file);

        InputStream stream = change.getStream();
        if (stream != null) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                IOUtils.copy(stream, output);
            }
            git.add().addFilepattern(change.getData().getName()).call();
            changedFiles.add(change.getData().getName());
        } else {
            if (file.exists()) {
                git.rm().addFilepattern(change.getData().getName()).call();
                changedFiles.add(change.getData().getName());
            }
        }
    }

    private RevCommit commitChangedFiles(CommitCommand commitCommand) throws GitAPIException {
        RevCommit commit;
        if (git.status().call().getUncommittedChanges().isEmpty()) {
            // For the cases:
            // 1) User modified a project, then manually reverted, then pressed save.
            // 2) Copy project that does not have rules.xml, check "Copy old revisions". The last one commit should
            // have changed rules.xml with changed project name but the project does not have rules.xml so there are
            // no changes
            // 3) Try to deploy several times same deploy configuration. For example if we need to trigger
            // webservices redeployment without actually changing projects.
            commit = commitCommand.setAllowEmpty(true).call();
        } else {
            commit = commitCommand.call();
        }
        return commit;
    }

    private void resolveAndMerge(FileData folderData,
            boolean checkoutOldVersion,
            RevCommit commit) throws GitAPIException, IOException {
        ConflictResolveData conflictResolveData = folderData.getAdditionalData(ConflictResolveData.class);
        RevCommit lastCommit = commit;

        if (conflictResolveData != null) {
            lastCommit = resolveConflict(folderData.getAuthor(), conflictResolveData);
        }

        if (checkoutOldVersion || conflictResolveData != null) {
            // Merge detached commit to existing branch.
            checkoutForced(branch);
            ObjectId commitId = lastCommit.getId();
            ObjectIdRef.Unpeeled ref = new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, commitId.name(), commitId.copy());
            String mergeMessage = getMergeMessage(folderData.getAuthor(), ref);
            MergeResult mergeDetached = git.merge().include(commitId).setMessage(mergeMessage).call();
            validateMergeConflict(mergeDetached, false);
        }
    }

    private RevCommit resolveConflict(String author, ConflictResolveData conflictResolveData) throws GitAPIException,
                                                                                              IOException {
        // Merge with a commit we have a conflict.
        MergeResult mergeResult = git.merge()
            .include(getCommitByVersion(conflictResolveData.getCommitToMerge()))
            .call();

        return resolveConflict(mergeResult, conflictResolveData, author);
    }

    private RevCommit resolveConflict(MergeResult mergeResult,
            ConflictResolveData conflictResolveData,
            String author) throws IOException, GitAPIException {
        if (mergeResult.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
            log.debug("Merge status: {}", mergeResult.getMergeStatus());
            throw new IOException("There is no merge conflict, nothing to resolve.");
        }

        // Resolve merge conflict.
        String mergeMessage = conflictResolveData.getMergeMessage();
        if (mergeMessage == null) {
            mergeMessage = "Merge";
        }
        CommitCommand conflictResolveCommit = git.commit()
            .setNoVerify(noVerify)
            .setMessage(mergeMessage)
            .setCommitter(userDisplayName != null ? userDisplayName : author, userEmail != null ? userEmail : "");

        Status status = git.status().call();

        Set<String> changedFiles = new HashSet<>();
        for (FileItem change : conflictResolveData.getResolvedFiles()) {
            applyChangeInWorkspace(change, changedFiles);
        }

        for (String changed : status.getChanged()) {
            if (!changedFiles.contains(changed)) {
                git.add().addFilepattern(changed).call();
                changedFiles.add(changed);
            }
        }
        for (String added : status.getAdded()) {
            if (!changedFiles.contains(added)) {
                git.add().addFilepattern(added).call();
                changedFiles.add(added);
            }
        }
        for (String removed : status.getRemoved()) {
            if (!changedFiles.contains(removed)) {
                git.rm().addFilepattern(removed).call();
                changedFiles.add(removed);
            }
        }

        return commitChangedFiles(conflictResolveCommit);
    }

    private ObjectId getCommitByVersion(String version) throws IOException {
        Ref ref = git.getRepository().findRef(version);
        if (ref == null) {
            // Version is a hash for commit
            return git.getRepository().resolve(version);
        }

        // Version is a tag.
        ObjectId objectId = git.getRepository().getRefDatabase().peel(ref).getPeeledObjectId();
        // Not annotated tags return null for getPeeledObjectId().
        return objectId == null ? ref.getObjectId() : objectId;
    }

    @Override
    public Features supports() {
        return new FeaturesBuilder(this).setSupportsUniqueFileId(true).build();
    }

    @Override
    public String getBranch() {
        return branch;
    }

    @Override
    public void createBranch(String projectPath, String newBranch) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("createBranch(): lock");
            writeLock.lock();

            reset();

            // If newBranch does not exist, create it.
            Ref branchRef = git.getRepository().findRef(newBranch);
            boolean branchAbsents = branchRef == null;
            if (branchAbsents) {
                // Checkout existing branch
                if (isEmpty()) {
                    throw new IOException("Can't create a branch on the empty repository.");
                }
                checkoutForced(branch);

                // Create new branch
                branchRef = git.branchCreate().setName(newBranch).call();
                pushBranch(new RefSpec().setSource(newBranch).setDestination(Constants.R_HEADS + newBranch));
            }

            lockSettings();
            try {
                BranchesData branches = getBranches(false);
                branches.addBranch(projectPath, branch, null);
                branches.addBranch(projectPath, newBranch, branchRef.getObjectId().getName());

                saveBranches();
            } finally {
                unlockSettings();
            }
        } catch (IOException e) {
            reset();
            try {
                git.branchDelete().setBranchNames(newBranch).call();
            } catch (Exception ignored) {
            }
            throw e;
        } catch (Exception e) {
            reset();
            try {
                git.branchDelete().setBranchNames(newBranch).call();
            } catch (Exception ignored) {
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("createBranch(): unlock");
        }
    }

    @Override
    public void deleteBranch(String projectPath, String branch) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("deleteBranch(): lock");
            writeLock.lock();

            reset();

            if (projectPath == null) {
                lockSettings();
                try {
                    BranchesData branches = getBranches(false);
                    // Remove the branch from all mappings.
                    if (branches.removeBranch(null, branch)) {
                        saveBranches();
                    }
                } finally {
                    unlockSettings();
                }

                // Remove the branch from git itself.
                // Cannot delete checked out branch. So we check out another branch instead.
                checkoutForced(baseBranch);
                git.branchDelete().setBranchNames(branch).setForce(true).call();
                pushBranch(new RefSpec().setSource(null).setDestination(Constants.R_HEADS + branch));
            } else {
                lockSettings();
                try {
                    BranchesData branches = getBranches(false);
                    // Remove branch mapping for specific project only.
                    if (branches.removeBranch(projectPath, branch)) {
                        saveBranches();
                    }
                } finally {
                    unlockSettings();
                }
            }
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("deleteBranch(): unlock");
        }
    }

    @Override
    public List<String> getBranches(String projectPath) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("getBranches(): lock");
            readLock.lock();
            BranchesData branches = getBranches(true);
            if (projectPath == null) {
                // Return all available branches
                TreeSet<String> branchNames = getAvailableBranches();

                // Local branches absent in repository may be needed to uncheck them in UI.
                for (List<String> projectBranches : branches.getProjectBranches().values()) {
                    branchNames.addAll(projectBranches);
                }

                return new ArrayList<>(branchNames);
            } else {
                // Return branches mapped to a specific project
                List<String> projectBranches = branches.getProjectBranches().get(projectPath);
                List<String> result;
                if (projectBranches == null) {
                    result = new ArrayList<>(Collections.singletonList(branch));
                } else {
                    result = new ArrayList<>(projectBranches);
                    result.sort(String.CASE_INSENSITIVE_ORDER);
                }
                return result;
            }
        } catch (GitAPIException e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("getBranches(): unlock");
        }
    }

    @Override
    public GitRepository forBranch(String branch) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("forBranch(): read: lock");
            readLock.lock();
            if (git.getRepository().findRef(branch) == null) {
                List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();

                boolean branchExist = false;
                String remoteBranchName = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branch;
                for (Ref ref : refs) {
                    String name = ref.getName();
                    if (remoteBranchName.equals(name)) {
                        branchExist = true;
                        break;
                    }
                }

                if (!branchExist) {
                    throw new IOException(String.format("Cannot find branch '%s'", branch));
                }
            }
        } catch (GitAPIException e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("forBranch(): read: unlock");
        }
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("forBranch(): write: lock");
            writeLock.lock();

            return createRepository(branch);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            writeLock.unlock();
            log.debug("forBranch(): write: unlock");
        }
    }

    private GitRepository createRepository(String branch) throws IOException, GitAPIException {
        if (git.getRepository().findRef(branch) == null) {
            createRemoteTrackingBranch(branch);
        }

        GitRepository repo = new GitRepository();

        repo.setId(id);
        repo.setName(name);
        repo.setUri(uri);
        repo.setLogin(login);
        repo.setPassword(password);
        repo.credentialsProvider = credentialsProvider;
        repo.setUserDisplayName(userDisplayName);
        repo.setUserEmail(userEmail);
        repo.setLocalRepositoryPath(localRepositoryPath);
        repo.setBranch(branch);
        repo.baseBranch = baseBranch; // Base branch is only one
        repo.setTagPrefix(tagPrefix);
        repo.setListenerTimerPeriod(listenerTimerPeriod);
        repo.setConnectionTimeout(connectionTimeout);
        repo.setCommentTemplate(commentTemplate);
        repo.setRepositorySettings(repositorySettings);
        repo.git = git;
        repo.repositoryLock = repositoryLock; // must be common for all instances because git
        // repository is same
        repo.remoteRepoLock = remoteRepoLock; // must be common for all instances because git
        // repository is same
        repo.branches = branches; // Can be shared between instances
        repo.monitor = monitor;
        return repo;
    }

    private void createRemoteTrackingBranch(String branch) throws GitAPIException {
        git.branchCreate()
            .setName(branch)
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .setStartPoint(Constants.DEFAULT_REMOTE_NAME + "/" + branch)
            .call();
    }

    TreeSet<String> getAvailableBranches() throws GitAPIException {
        TreeSet<String> branchNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        List<Ref> refs = git.branchList().call();
        for (Ref ref : refs) {
            String name = ref.getName();
            if (name.startsWith(Constants.R_HEADS)) {
                name = name.substring(Constants.R_HEADS.length());
                branchNames.add(name);
            }
        }
        return branchNames;
    }

    private void pushBranch(RefSpec refSpec) throws GitAPIException, IOException {
        if (uri == null) {
            return;
        }

        PushCommand push = git.push().setRefSpecs(refSpec).setTimeout(connectionTimeout);

        CredentialsProvider credentialsProvider = getCredentialsProvider(GitActionType.PUSH_BRANCH);
        if (credentialsProvider != null) {
            push.setCredentialsProvider(credentialsProvider);
        }

        Iterable<PushResult> results = push.call();
        successAuthentication(GitActionType.PUSH_BRANCH);
        validatePushResults(results);
    }

    private void readBranches() throws IOException {
        if (repositorySettings == null) {
            return;
        }

        settingsSyncDate = repositorySettings.getSyncDate();
        FileItem fileItem = repositorySettings.getRepository().read(branchesConfigFile);
        if (fileItem != null) {
            if (settingsSyncDate.before(fileItem.getData().getModifiedAt())) {
                settingsSyncDate = fileItem.getData().getModifiedAt();
            }
            try (InputStreamReader in = new InputStreamReader(fileItem.getStream(), StandardCharsets.UTF_8)) {
                TypeDescription projectsDescription = new TypeDescription(BranchesData.class);
                projectsDescription.addPropertyParameters("descriptions", BranchDescription.class);
                Constructor constructor = new Constructor(BranchesData.class);
                constructor.addTypeDescription(projectsDescription);
                Representer representer = new Representer();
                representer.getPropertyUtils().setSkipMissingProperties(true);
                Yaml yaml = new Yaml(constructor, representer);
                branches.copyFrom(yaml.loadAs(in, BranchesData.class));
            }
        }
    }

    private void saveBranches() throws IOException {
        if (repositorySettings == null) {
            return;
        }
        DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (OutputStreamWriter out = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            yaml.dump(branches, out);
        }

        FileData data = new FileData();
        data.setName(branchesConfigFile);
        data.setAuthor(getClass().getName());
        data.setComment("Update branches info");
        repositorySettings.getRepository().save(data, new ByteArrayInputStream(outputStream.toByteArray()));
    }

    private void removeAbsentFiles(String baseAbsolutePath,
            File directory,
            Collection<File> toSave) throws GitAPIException {
        File[] found = directory.listFiles();

        if (found != null) {
            for (File file : found) {
                if (file.isDirectory()) {
                    removeAbsentFiles(baseAbsolutePath, file, toSave);
                } else {
                    if (!toSave.contains(file)) {
                        String relativePath = file.getAbsolutePath()
                            .substring(baseAbsolutePath.length())
                            .replace('\\', '/');
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }
                        git.rm().addFilepattern(relativePath).call();
                    }
                }
            }
        }
    }

    private void createParent(File file) throws FileNotFoundException {
        File parentFile = file.getParentFile();
        if (!parentFile.mkdirs() && !parentFile.exists()) {
            throw new FileNotFoundException("Cannot create the folder " + parentFile.getAbsolutePath());
        }
    }

    private URI getUri(String uriOrPath) {
        if (uriOrPath == null) {
            return null;
        }
        try {
            return new URL(uriOrPath).toURI();
        } catch (URISyntaxException | MalformedURLException e) {
            // uri can be a folder path. It's not valid URI but git accepts paths too.
            return new File(uriOrPath).toURI();
        }
    }

    private String escapeCurlyBrackets(String value) {
        String ret = value.replaceAll("\\{(?![012]})", "'{'");
        return ret.replaceAll("(?<!\\{[012])}", "'}'");
    }

    private String formatComment(CommitType commitType, FileData data) {
        String comment = StringUtils.trimToEmpty(data.getComment());
        return MessageFormat.format(escapedCommentTemplate, commitType, comment, data.getAuthor());
    }

    private String getMergeMessage(String mergeAuthor, Ref r) throws IOException {
        String userMessage = new MergeMessageFormatter().format(Collections.singletonList(r),
            git.getRepository().exactRef(Constants.HEAD));
        return MessageFormat.format(escapedCommentTemplate, CommitType.MERGE, userMessage, mergeAuthor);
    }

    private void unlockSettings() {
        if (repositorySettings != null) {
            repositorySettings.unlock(branchesConfigFile);
        }
    }

    private void lockSettings() throws IOException {
        if (repositorySettings != null) {
            repositorySettings.lock(branchesConfigFile);
        }
    }

    boolean isCheckoutOldVersion(String path, String baseVersion) throws GitAPIException, IOException {
        if (baseVersion != null) {
            List<Ref> tags = git.tagList().call();

            RevCommit commit = findFirstCommit(git, resolveBranchId(), path);
            if (commit != null) {
                String lastVersion = getVersionName(git.getRepository(), tags, commit);
                return !baseVersion.equals(lastVersion);
            } else {
                throw new FileNotFoundException(
                    String.format("Cannot find commit for path '%s' and version '%s'", path, baseVersion));
            }
        }

        return false;
    }

    @Override
    public boolean isValidBranchName(String s) {
        return s != null && Repository.isValidRefName(Constants.R_HEADS + s);
    }

    @Override
    public boolean branchExists(String branch) throws IOException {
        for (String existedBranch : getBranches(null)) {
            if (existedBranch.equalsIgnoreCase(branch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns Git repository that can safely be closed.<br/>
     * If our instance of GitRepository isn't closed, returns Git object that reuses existing Repository instance. In
     * this case close() method does nothing: Repository object is managed by GitRepository.<br/>
     * If our instance of GitRepository is closed, returns Git object with a new Repository instance so the caller must
     * close it.<br/>
     * So we have a general rule: when you use getClosableGit(), you must always close returned object.
     */
    Git getClosableGit() throws IOException {
        if (closed) {
            return Git.open(new File(localRepositoryPath));
        } else {
            return new Git(git.getRepository());
        }
    }

    private CredentialsProvider getCredentialsProvider(GitActionType actionType) {
        if (credentialsProvider != null) {
            credentialsProvider.validateAuthorizationState(actionType);
        }
        return credentialsProvider;
    }

    private void successAuthentication(GitActionType actionType) {
        if (credentialsProvider != null) {
            credentialsProvider.successAuthentication(actionType);
        }
    }

    private class GitRevisionGetter implements RevisionGetter {
        @Override
        public Object getRevision() {
            try {
                return getLastRevision();
            } catch (Exception e) {
                log.warn(e.getMessage(), e);
                return null;
            }
        }
    }

    public interface WalkCommand<T> {
        T apply(org.eclipse.jgit.lib.Repository repository, TreeWalk rootWalk, String baseFolder) throws IOException,
                                                                                                  GitAPIException;
    }

    public interface HistoryVisitor<T> {
        /**
         * Visit commit for a file with a path {@code fullPath}
         *
         * @param fullPath full path to the file
         * @param commit visiting commit
         * @param commitVersion commit version. Either tag name or commit hash.
         * @return true if we should stop iterating history (we found needed information) and false if not found or
         *         should iterate all commits
         */
        boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException, GitAPIException;

        /**
         * Get accumulated result
         */
        T getResult();
    }

    private class ListCommand implements WalkCommand<List<FileData>> {

        private final ObjectId start;
        private final RevCommit revCommit;

        private ListCommand(ObjectId start) {
            this.start = start;
            this.revCommit = null;
        }

        private ListCommand(RevCommit revCommit) {
            this.start = revCommit.getId();
            this.revCommit = revCommit;
        }

        @Override
        public List<FileData> apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                // Iterate files in folder
                List<FileData> files = new ArrayList<>();
                if (rootWalk.getFilter() == TreeFilter.ALL) {
                    while (rootWalk.next()) {
                        files.add(createFileData(rootWalk, baseFolder, start));
                    }
                } else {
                    if (rootWalk.getTreeCount() > 0) {
                        try (TreeWalk dirWalk = new TreeWalk(repository)) {
                            dirWalk.addTree(rootWalk.getObjectId(0));
                            dirWalk.setRecursive(true);
                            while (dirWalk.next()) {
                                if (revCommit != null) {
                                    files.add(new LazyFileData(branch,
                                        baseFolder + dirWalk.getPathString(),
                                        GitRepository.this,
                                        revCommit,
                                        getFileId(dirWalk),
                                        commitMessageParser));
                                } else {
                                    files.add(createFileData(dirWalk, baseFolder, start));
                                }
                            }
                        }
                    }
                }

                return files;
            } else {
                return Collections.emptyList();
            }
        }
    }

    private class ListFoldersCommand implements WalkCommand<List<FileData>> {
        @Override
        public List<FileData> apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                if (rootWalk.getFilter() == TreeFilter.ALL) {
                    return collectFolderData(rootWalk, baseFolder);
                } else {
                    if (rootWalk.getTreeCount() > 0) {
                        try (TreeWalk dirWalk = new TreeWalk(repository)) {
                            dirWalk.addTree(rootWalk.getObjectId(0));
                            return collectFolderData(dirWalk, baseFolder);
                        }
                    }
                }
            }

            return Collections.emptyList();
        }

        private List<FileData> collectFolderData(TreeWalk rootWalk, String baseFolder) throws IOException {
            List<FileData> files = new ArrayList<>();
            rootWalk.setRecursive(false);
            ObjectId start = resolveBranchId();
            while (rootWalk.next()) {
                if ((rootWalk.getFileMode().getBits() & FileMode.TYPE_TREE) != 0) {
                    files.add(createFileData(rootWalk, baseFolder, start));
                }
            }

            return files;
        }
    }

    private class CheckCommand implements WalkCommand<FileData> {
        @Override
        public FileData apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null && StringUtils.isNotEmpty(baseFolder)) {
                return createFileData(rootWalk, "", resolveBranchId());
            } else {
                return null;
            }
        }
    }

    private class ReadCommand implements WalkCommand<FileItem> {
        @Override
        public FileItem apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null && StringUtils.isNotEmpty(baseFolder)) {
                FileData fileData = createFileData(rootWalk, "", resolveBranchId());
                ObjectLoader loader = repository.open(rootWalk.getObjectId(0));
                return new FileItem(fileData, loader.openStream());
            } else {
                return null;
            }
        }
    }

    private class ListHistoryVisitor implements HistoryVisitor<List<FileData>> {
        private final org.eclipse.jgit.lib.Repository repository;
        private final List<FileData> history = new ArrayList<>();

        private ListHistoryVisitor() {
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            RevTree tree = commit.getTree();

            try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                history.add(createFileData(rootWalk, commit));
            } catch (FileNotFoundException e) {
                log.debug("File '{}' is absent in the commit {}", fullPath, commitVersion, e);
                FileData data = new LazyFileData(branch,
                    fullPath,
                    GitRepository.this,
                    commit,
                    null,
                    commitMessageParser);
                // Must mark it as deleted explicitly because the file can be erased outside of WebStudio.
                data.setDeleted(true);

                history.add(data);
            }

            return false;
        }

        @Override
        public List<FileData> getResult() {
            Collections.reverse(history);
            return history;
        }
    }

    private class ListFilesHistoryVisitor implements HistoryVisitor<List<FileData>> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private final List<FileData> history = new ArrayList<>();

        private ListFilesHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    history.addAll(new ListCommand(commit).apply(repository, rootWalk, fullPath));
                } catch (FileNotFoundException ignored) {
                }

                return true;
            }

            return false;
        }

        @Override
        public List<FileData> getResult() {
            Collections.reverse(history);
            return history;
        }
    }

    private class CheckHistoryVisitor implements HistoryVisitor<FileData> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private FileData result;

        private CheckHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    result = createFileData(rootWalk, commit);
                } catch (FileNotFoundException e) {
                    result = null;
                }

                return true;
            }

            return false;
        }

        @Override
        public FileData getResult() {
            return result;
        }
    }

    private class ReadHistoryVisitor implements HistoryVisitor<FileItem> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private FileItem result;

        private ReadHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    FileData fileData = createFileData(rootWalk, commit);
                    ObjectLoader loader = repository.open(rootWalk.getObjectId(0));
                    result = new FileItem(fileData, loader.openStream());
                } catch (FileNotFoundException e) {
                    result = null;
                }

                return true;
            }

            return false;
        }

        @Override
        public FileItem getResult() {
            return result;
        }
    }

    static boolean isSame(URI a, URI b) {
        if (!Objects.equals(a.getRawFragment(), b.getRawFragment())) {
            return false;
        }
        Function<String, String> remLastSlash = s -> {
            int i = s.length();
            while (i > 0 && s.charAt(i - 1) == '/') {
                i--;
            }
            if (i != s.length() && s.charAt(i) == '/') {
                return s.substring(0, i);
            } else {
                return s;
            }
        };
        if (!Objects.equals(a.getRawSchemeSpecificPart(), b.getRawSchemeSpecificPart())) {
            if (!Objects.equals(remLastSlash.apply(a.getRawSchemeSpecificPart()),
                    remLastSlash.apply(b.getRawSchemeSpecificPart()))) {
                return false;
            }
        }
        if (!Objects.equals(a.getRawPath(), b.getRawPath())) {
            if (!Objects.equals(remLastSlash.apply(a.getRawPath()), remLastSlash.apply(b.getRawPath()))) {
                return false;
            }
        }
        if (!Objects.equals(a.getRawQuery(), b.getRawQuery())) {
            return false;
        }
        if (!Objects.equals(a.getRawAuthority(), b.getRawAuthority())) {
            return false;
        }
        if (a.getHost() != null) {
            if (!Objects.equals(a.getRawUserInfo(), b.getRawUserInfo())) {
                return false;
            }
            if (!a.getHost().equalsIgnoreCase(b.getHost())) {
                return false;
            }
            if (a.getPort() != b.getPort()) {
                return false;
            }
        }
        return Objects.equals(a.getRawAuthority(), b.getRawAuthority());
    }
}
