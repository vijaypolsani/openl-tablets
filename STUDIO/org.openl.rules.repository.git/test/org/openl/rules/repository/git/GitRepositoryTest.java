package org.openl.rules.repository.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.openl.rules.repository.git.TestGitUtils.assertContains;
import static org.openl.rules.repository.git.TestGitUtils.createFileData;
import static org.openl.rules.repository.git.TestGitUtils.createNewFile;
import static org.openl.rules.repository.git.TestGitUtils.writeText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openl.rules.repository.api.BranchRepository;
import org.openl.rules.repository.api.ChangesetType;
import org.openl.rules.repository.api.ConflictResolveData;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.FileItem;
import org.openl.rules.repository.api.FolderItem;
import org.openl.rules.repository.api.Listener;
import org.openl.rules.repository.api.MergeConflictException;
import org.openl.rules.repository.api.RepositorySettings;
import org.openl.rules.repository.file.FileSystemRepository;
import org.openl.util.FileUtils;
import org.openl.util.IOUtils;

public class GitRepositoryTest {
    private static final String BRANCH = "test";
    private static final String FOLDER_IN_REPOSITORY = "rules/project1";
    private static final String TAG_PREFIX = "Rules_";

    private static File template;
    private File root;
    private GitRepository repo;
    private ChangesCounter changesCounter;

    @BeforeClass
    public static void initTest() throws GitAPIException, IOException {
        template = Files.createTempDirectory("openl-template").toFile();

        // Initialize remote repository
        try (Git git = Git.init().setDirectory(template).call()) {
            Repository repository = git.getRepository();
            StoredConfig config = repository.getConfig();
            config.setBoolean(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTODETACH, false);
            config.save();

            File parent = repository.getDirectory().getParentFile();
            File rulesFolder = new File(parent, FOLDER_IN_REPOSITORY);

            // create initial commit in master
            createNewFile(parent, "file-in-master", "root");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("Initial").setCommitter("user1", "user1@mail.to").call();
            addTag(git, commit, 1);

            // create first commit in test branch
            git.branchCreate().setName(BRANCH).call();
            git.checkout().setName(BRANCH).call();

            createNewFile(parent, "file-in-test", "root");
            createNewFile(rulesFolder, "file1", "Hi.");
            File file2 = createNewFile(rulesFolder, "file2", "Hello.");
            git.add().addFilepattern(".").call();
            commit = git.commit()
                .setMessage("Initial commit in test branch")
                .setCommitter("user1", "user1@mail.to")
                .call();
            addTag(git, commit, 2);

            // create second commit
            writeText(file2, "Hello World.");
            createNewFile(new File(rulesFolder, "folder"), "file3", "In folder");
            git.add().addFilepattern(".").call();
            commit = git.commit()
                .setAll(true)
                .setMessage("Second modification")
                .setCommitter("user2", "user2@gmail.to")
                .call();
            addTag(git, commit, 3);

            // create commit in master
            git.checkout().setName(Constants.MASTER).call();
            createNewFile(rulesFolder, "file1master", "root");
            git.add().addFilepattern(".").call();
            commit = git.commit()
                .setMessage("Additional commit in master")
                .setCommitter("user1", "user1@mail.to")
                .call();
            addTag(git, commit, 4);
        }
    }

    @AfterClass
    public static void clearTest() throws IOException {
        FileUtils.delete(template);
        if (template.exists()) {
            fail("Cannot delete folder " + template);
        }
    }

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("openl").toFile();

        File remote = new File(root, "remote");
        File local = new File(root, "local");

        FileUtils.copy(template, remote);
        repo = createRepository(remote, local);

        changesCounter = new ChangesCounter();
        repo.setListener(changesCounter);
    }

    @After
    public void tearDown() {
        if (repo != null) {
            repo.close();
        }
        FileUtils.deleteQuietly(root);
        if (root.exists()) {
            fail("Cannot delete folder " + root);
        }
    }

    @Test
    public void list() throws IOException {
        assertEquals(5, repo.list("").size());

        List<FileData> files = repo.list("rules/project1/");
        assertNotNull(files);
        assertEquals(3, files.size());

        FileData file1 = getFileData(files, "rules/project1/file1");
        assertNotNull(file1);
        assertEquals("user1", file1.getAuthor());
        assertEquals("Initial commit in test branch", file1.getComment());
        assertEquals(3, file1.getSize());

        FileData file2 = getFileData(files, "rules/project1/file2");
        assertNotNull(file2);
        assertEquals("user2", file2.getAuthor());
        assertEquals("Second modification", file2.getComment());
        assertEquals(12, file2.getSize());

        FileData file3 = getFileData(files, "rules/project1/folder/file3");
        assertNotNull(file3);
        assertEquals("user2", file3.getAuthor());
        assertEquals("Second modification", file3.getComment());
        assertEquals(9, file3.getSize());
    }

    @Test
    public void listFolders() throws IOException {
        assertEquals(1, repo.listFolders("").size());

        List<FileData> folders = repo.listFolders("rules/");
        assertNotNull(folders);
        assertEquals(1, folders.size());

        FileData folderData = folders.get(0);
        assertEquals("rules/project1", folderData.getName());
    }

    @Test
    public void listFiles() throws IOException {
        List<FileData> files = repo.listFiles("rules/project1/", "Rules_2");
        assertNotNull(files);
        assertEquals(2, files.size());
        assertContains(files, "rules/project1/file1");
        assertContains(files, "rules/project1/file2");

        FileData file1Rev2 = find(files, "rules/project1/file1");
        assertEquals("Rules_2", file1Rev2.getVersion());

        FileData file2Rev2 = find(files, "rules/project1/file2");
        assertEquals("Rules_2", file2Rev2.getVersion());
        assertEquals("user1", file2Rev2.getAuthor());
        assertEquals("Initial commit in test branch", file2Rev2.getComment());
        assertEquals("Expected file content: 'Hello!'", 6, file2Rev2.getSize());

        files = repo.listFiles("rules/project1/", "Rules_3");
        assertNotNull(files);
        assertEquals(3, files.size());
        assertContains(files, "rules/project1/file1");
        assertContains(files, "rules/project1/file2");
        assertContains(files, "rules/project1/folder/file3");

        // Each file has last modified project version, to performance improve
        // FileData file1Rev3 = find(files, "rules/project1/file1");
        // assertEquals("Rules_2", file1Rev3.getVersion()); // The file has not been modified in second commit

        FileData file2Rev3 = find(files, "rules/project1/file2");
        assertEquals("Rules_3", file2Rev3.getVersion());
        assertEquals("user2", file2Rev3.getAuthor());
        assertEquals("Second modification", file2Rev3.getComment());
        assertEquals("Expected file content: 'Hello World!'", 12, file2Rev3.getSize());

        FileData file3Rev3 = find(files, "rules/project1/folder/file3");
        assertEquals("Rules_3", file3Rev3.getVersion());
    }

    @Test
    public void check() throws IOException {
        FileData file1 = repo.check("rules/project1/file1");
        assertNotNull(file1);
        assertEquals("user1", file1.getAuthor());
        assertEquals("Initial commit in test branch", file1.getComment());
        assertEquals(3, file1.getSize());

        FileData file2 = repo.check("rules/project1/file2");
        assertNotNull(file2);
        assertEquals("user2", file2.getAuthor());
        assertEquals("Second modification", file2.getComment());
        assertEquals(12, file2.getSize());

        FileData file3 = repo.check("rules/project1/folder/file3");
        assertNotNull(file3);
        assertEquals("user2", file3.getAuthor());
        assertEquals("Second modification", file3.getComment());
        assertEquals(9, file3.getSize());

        FileData project1 = repo.check("rules/project1");
        assertNotNull(project1);
        assertEquals("rules/project1", project1.getName());
        assertEquals("user2", project1.getAuthor());
        assertEquals("Second modification", project1.getComment());
        assertEquals(FileData.UNDEFINED_SIZE, project1.getSize());
    }

    @Test
    public void read() throws IOException {
        assertEquals("Hi.", IOUtils.toStringAndClose(repo.read("rules/project1/file1").getStream()));
        assertEquals("Hello World.", IOUtils.toStringAndClose(repo.read("rules/project1/file2").getStream()));
        assertEquals("In folder", IOUtils.toStringAndClose(repo.read("rules/project1/folder/file3").getStream()));

        assertEquals(0, changesCounter.getChanges());
    }

    @Test
    public void save() throws IOException {
        // Create a new file
        String path = "rules/project1/folder/file4";
        String text = "File located in " + path;
        FileData result = repo.save(createFileData(path, text), IOUtils.toInputStream(text));

        assertNotNull(result);
        assertEquals(path, result.getName());
        assertEquals("John Smith", result.getAuthor());
        assertEquals("Comment for rules/project1/folder/file4", result.getComment());
        assertEquals(text.length(), result.getSize());
        assertEquals("Rules_5", result.getVersion());
        assertNotNull(result.getModifiedAt());

        assertEquals(text, IOUtils.toStringAndClose(repo.read("rules/project1/folder/file4").getStream()));

        // Modify existing file
        text = "Modified";
        result = repo.save(createFileData(path, text), IOUtils.toInputStream(text));
        assertNotNull(result);
        assertEquals(text.length(), result.getSize());
        assertEquals("Rules_6", result.getVersion());
        assertEquals(text, IOUtils.toStringAndClose(repo.read("rules/project1/folder/file4").getStream()));

        assertEquals(2, changesCounter.getChanges());

        // Clone remote repository to temp folder and check that changes we made before exist there
        File remote = new File(root, "remote");
        File temp = new File(root, "temp");
        try (GitRepository secondRepo = createRepository(remote, temp)) {
            assertEquals(text, IOUtils.toStringAndClose(secondRepo.read("rules/project1/folder/file4").getStream()));
        }

        // Check that creating new folders works correctly
        path = "rules/project1/new-folder/file5";
        text = "File located in " + path;
        assertNotNull(repo.save(createFileData(path, text), IOUtils.toInputStream(text)));
    }

    @Test
    public void saveFolder() throws IOException {
        List<FileItem> changes = Arrays.asList(
            new FileItem("rules/project1/new-path/file4", IOUtils.toInputStream("Added")),
            new FileItem("rules/project1/file2", IOUtils.toInputStream("Modified")));

        FileData folderData = new FileData();
        folderData.setName("rules/project1");
        folderData.setAuthor("John Smith");
        folderData.setComment("Bulk change");

        FileData savedData = repo.save(folderData, changes, ChangesetType.FULL);
        assertNotNull(savedData);
        List<FileData> files = repo.list("rules/project1/");
        assertContains(files, "rules/project1/new-path/file4");
        assertContains(files, "rules/project1/file2");
        assertEquals(2, files.size());

        // Save second time without changes. Mustn't fail.
        changes.get(0).getStream().reset();
        changes.get(1).getStream().reset();
        assertNotNull(repo.save(folderData, changes, ChangesetType.FULL));

        for (FileItem file : changes) {
            IOUtils.closeQuietly(file.getStream());
        }
    }

    @Test
    public void delete() throws IOException {
        FileData fileData = new FileData();
        fileData.setName("rules/project1/file2");
        fileData.setComment("Delete file 2");
        fileData.setAuthor("John Smith");
        boolean deleted = repo.delete(fileData);
        assertTrue("'file2' has not been deleted", deleted);

        assertNull("'file2' still exists", repo.check("rules/project1/file2"));

        // Count actual changes in history
        String projectPath = "rules/project1";
        assertEquals(3, repo.listHistory(projectPath).size());

        // Archive the project
        FileData projectData = new FileData();
        projectData.setName(projectPath);
        projectData.setComment("Delete project1");
        projectData.setAuthor("John Smith");
        assertTrue("'project1' has not been deleted", repo.delete(projectData));

        FileData deletedProject = repo.check(projectPath);
        assertTrue("'project1' is not deleted", deletedProject.isDeleted());

        // Restore the project
        FileData toDelete = new FileData();
        toDelete.setName(projectPath);
        toDelete.setVersion(deletedProject.getVersion());
        toDelete.setComment("Delete project1.");
        assertTrue(repo.deleteHistory(toDelete));
        deletedProject = repo.check(projectPath);
        assertFalse("'project1' is not restored", deletedProject.isDeleted());
        assertEquals("Delete project1.", deletedProject.getComment());

        // Count actual changes in history
        assertEquals("Actual project changes must be 5.", 5, repo.listHistory(projectPath).size());

        // Erase the project
        toDelete.setName(projectPath);
        toDelete.setVersion(null);
        toDelete.setComment("Erase project1");
        assertTrue(repo.deleteHistory(toDelete));
        deletedProject = repo.check(projectPath);
        assertNull("'project1' is not erased", deletedProject);

        // Life after erase
        List<FileData> versionsAfterErase = repo.listHistory(projectPath);
        assertEquals(6, versionsAfterErase.size());
        FileData erasedData = versionsAfterErase.get(versionsAfterErase.size() - 1);
        assertTrue(erasedData.isDeleted());
        assertEquals(0, repo.listFiles(projectPath, erasedData.getVersion()).size());

        // Create new version
        String text = "Reincarnation";
        repo.save(createFileData(projectPath + "/folder/reincarnate", text), IOUtils.toInputStream(text));
        assertEquals(7, repo.listHistory(projectPath).size());

        // manually add the file with name ".archived". It shouldn't prevent to delete the project
        repo.save(createFileData(projectPath + "/" + GitRepository.DELETED_MARKER_FILE, ""), IOUtils.toInputStream(""));
        assertTrue("'project1' has not been deleted", repo.delete(projectData));
        assertTrue("'project1' is not deleted", repo.check(projectPath).isDeleted());
    }

    @Test(timeout = 10_000)
    public void deleteAndSwitchBranches() throws IOException, GitAPIException {
        repo.createBranch(FOLDER_IN_REPOSITORY, "test1");
        GitRepository repo2 = repo.forBranch("test1");

        final String name = FOLDER_IN_REPOSITORY;

        // Archive the project in main branch
        FileData fileData = new FileData();
        fileData.setName(name);
        fileData.setComment("Delete project1");
        fileData.setAuthor("John Smith");
        boolean deleted = repo.delete(fileData);
        assertTrue("'file2' has not been deleted", deleted);

        // Check that the project is archived in main branch
        assertEquals(BRANCH, repo.getBranch());
        final FileData archived = repo.check(name);
        assertTrue(archived.isDeleted());

        // Check that the project is archived in secondary branch too
        assertTrue("In repository with flat folder structure deleted status should be gotten from main branch",
            repo2.check(name).isDeleted());

        // Undelete the project
        assertTrue(repo.deleteHistory(archived));
        FileData undeleted = repo.check(name);

        // Check that the project is undeleted in main branch
        assertFalse(undeleted.isDeleted());

        // Check that the project is undeleted in secondary branch too
        assertFalse("In repository with flat folder structure deleted status should be gotten from main branch",
            repo2.check(name).isDeleted());

        // Check that old archived version is still deleted.
        assertTrue(repo.checkHistory(name, archived.getVersion()).isDeleted());

        // Check that isDeleted() isn't broken for files: their status shouldn't be get from main branch.
        String filePath = "rules/project1/folder/file-new";
        String text = "text";
        FileData created = repo2.save(createFileData(filePath, text), IOUtils.toInputStream(text));
        assertFalse(created.isDeleted());
        assertFalse(repo2.check(filePath).isDeleted());
        assertFalse(repo2.checkHistory(filePath, created.getVersion()).isDeleted());

        // Delete the project outside of OpenL
        deleteProjectOutsideOfOpenL(repo2);
        // Recreate a project
        assertNotNull(repo2.save(createFileData(filePath, text), IOUtils.toInputStream(text)));
        // Check that the commit with project erasing can be read. There should be no deadlock.
        List<FileData> history = repo2.listHistory(name);
        assertTrue("Not enough history records", history.size() > 2);
        FileData erasedData = history.get(history.size() - 2);
        assertTrue(erasedData.isDeleted());
    }

    private void deleteProjectOutsideOfOpenL(GitRepository repo) throws IOException, GitAPIException {
        try (Git git = repo.getClosableGit()) {
            git.checkout().setName(repo.getBranch()).setForced(true).call();
            git.rm().addFilepattern(FOLDER_IN_REPOSITORY).call();
            git.commit().setMessage("External erase").setCommitter("user1", "user1@mail.to").call();
        }
    }

    @Test
    public void listHistory() throws IOException {
        List<FileData> file2History = repo.listHistory("rules/project1/file2");
        assertEquals(2, file2History.size());
        assertEquals("Rules_2", file2History.get(0).getVersion());
        assertEquals("Rules_3", file2History.get(1).getVersion());

        List<FileData> project1History = repo.listHistory("rules/project1");
        assertEquals(2, project1History.size());
        assertEquals("Rules_2", project1History.get(0).getVersion());
        assertEquals("Rules_3", project1History.get(1).getVersion());

        assertEquals(1, repo.listHistory("rules/project1/folder").size());
    }

    @Test
    public void checkHistory() throws IOException {
        assertEquals("Rules_2", repo.checkHistory("rules/project1/file2", "Rules_2").getVersion());
        assertEquals("Rules_3", repo.checkHistory("rules/project1/file2", "Rules_3").getVersion());
        assertNull(repo.checkHistory("rules/project1/file2", "Rules_1"));

        FileData v3 = repo.checkHistory("rules/project1", "Rules_3");
        assertEquals("Rules_3", v3.getVersion());
        assertEquals("user2", v3.getAuthor());

        FileData v2 = repo.checkHistory("rules/project1", "Rules_2");
        assertEquals("Rules_2", v2.getVersion());
        assertEquals("user1", v2.getAuthor());

        assertNull(repo.checkHistory("rules/project1", "Rules_1"));
    }

    @Test
    public void readHistory() throws IOException {
        assertEquals("Hello.",
            IOUtils.toStringAndClose(repo.readHistory("rules/project1/file2", "Rules_2").getStream()));
        assertEquals("Hello World.",
            IOUtils.toStringAndClose(repo.readHistory("rules/project1/file2", "Rules_3").getStream()));
        assertNull(repo.readHistory("rules/project1/file2", "Rules_1"));
    }

    @Test
    public void copyHistory() throws IOException {
        FileData dest = new FileData();
        dest.setName("rules/project1/file2-copy");
        dest.setComment("Copy file 2");
        dest.setAuthor("John Smith");

        FileData copy = repo.copyHistory("rules/project1/file2", dest, "Rules_2");
        assertNotNull(copy);
        assertEquals("rules/project1/file2-copy", copy.getName());
        assertEquals("John Smith", copy.getAuthor());
        assertEquals("Copy file 2", copy.getComment());
        assertEquals(6, copy.getSize());
        assertEquals("Rules_5", copy.getVersion());
        assertEquals("Hello.", IOUtils.toStringAndClose(repo.read("rules/project1/file2-copy").getStream()));

        FileData destProject = new FileData();
        destProject.setName("rules/project2");
        destProject.setComment("Copy of project1");
        destProject.setAuthor("John Smith");
        FileData project2 = repo.copyHistory("rules/project1", destProject, "Rules_2");
        assertNotNull(project2);
        assertEquals("rules/project2", project2.getName());
        assertEquals("John Smith", project2.getAuthor());
        assertEquals("Copy of project1", project2.getComment());
        assertEquals(FileData.UNDEFINED_SIZE, project2.getSize());
        assertEquals("Rules_6", project2.getVersion());
        List<FileData> project2Files = repo.list("rules/project2/");
        assertEquals(2, project2Files.size());
        assertContains(project2Files, "rules/project2/file1");
        assertContains(project2Files, "rules/project2/file2");
    }

    @Test
    public void changesShouldBeRolledBackOnError() throws Exception {
        try {
            FileData data = new FileData();
            data.setName("rules/project1/file2");
            data.setAuthor(null);
            data.setComment(null);
            repo.save(data, IOUtils.toInputStream("error"));
            fail("Exception should be thrown");
        } catch (IOException e) {
            assertEquals("Name of PersonIdent must not be null.", e.getCause().getMessage());
        }

        // Check that there are no uncommitted changes after error
        try (Git git = Git.open(new File(root, "local"))) {
            Status status = git.status().call();
            assertTrue(status.getUncommittedChanges().isEmpty());
        }
    }

    @Test
    public void repoFolderExistsButEmpty() throws IOException {
        // Prepare the test: the folder with local repository name exists but it's empty
        repo.close();

        File remote = new File(root, "remote");
        File local = new File(root, "local");
        FileUtils.deleteQuietly(local);
        assertFalse("Cannot delete repository. It shouldn't be locked.", local.exists());

        if (!local.mkdirs() && !local.exists()) {
            fail("Cannot create the folder for test");
        }

        // Check that repo is cloned successfully
        try (GitRepository repository = createRepository(remote, local)) {
            assertEquals(5, repository.list("").size());
        }
        // Reuse cloned before repository. Must not fail.
        try (GitRepository repository = createRepository(remote, local)) {
            assertEquals(5, repository.list("").size());
        }
    }

    @Test
    public void neededBranchWasNotClonedBefore() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local = new File(root, "temp");
        try (GitRepository repository = createRepository(remote, local, Constants.MASTER)) {
            assertEquals(2, repository.list("").size());
        }

        // Check: second time initialize the repo. At this time use the branch "test". It must be pulled
        // successfully and repository must be switched to that branch.
        try (GitRepository repository = createRepository(remote, local)) {
            assertEquals(5, repository.list("").size());

            // Check that changes are saved to correct branch.
            String text = "New file";
            FileItem change1 = new FileItem("rules/project-second/new/file1", IOUtils.toInputStream(text));
            FileItem change2 = new FileItem("rules/project-second/new/file2", IOUtils.toInputStream(text));
            FileData newProjectData = createFileData("rules/project-second/new", text);
            repository.save(newProjectData, Arrays.asList(change1, change2), ChangesetType.FULL);
            assertEquals(7, repository.list("").size());
        }
    }

    @Test
    public void twoUsersAddFileSimultaneously() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        // First user starts to save it's changes
        try (GitRepository repository1 = createRepository(remote, local1)) {
            String text = "New file";

            // Second user is quicker than first
            FileData saved2;
            try (GitRepository repository2 = createRepository(remote, local2)) {
                saved2 = repository2.save(createFileData("rules/project-second/file2", text),
                    IOUtils.toInputStream(text));
            }

            // First user does not suspect that second user already committed his changes
            FileData saved1 = repository1.save(createFileData("rules/project-first/file1", text),
                IOUtils.toInputStream(text));

            // Check that the changes of both users are persist and merged
            assertNotEquals("Versions of two changes must be different.", saved1.getVersion(), saved2.getVersion());
            assertEquals("5 files existed and 2 files must be added (must be 7 files in total).",
                7,
                repository1.list("").size());
            assertEquals("Rules_6", saved1.getVersion());
            assertEquals("Rules_5", saved2.getVersion());
            assertEquals(repository1.check(saved1.getName()).getVersion(), "Rules_6");
            assertEquals("Rules_6", repository1.listHistory(saved1.getName()).get(0).getVersion());

            // Just ensure that last commit in the whole repository is merge commit
            assertEquals("Merge branch 'test' into test", repository1.check("rules").getComment());
        }
    }

    @Test
    public void mergeConflictInFile() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        String baseCommit = null;
        String theirCommit = null;

        final String filePath = "rules/project1/file2";

        try (GitRepository repository1 = createRepository(remote, local1);
                GitRepository repository2 = createRepository(remote, local2)) {
            try {
                baseCommit = repository1.check(filePath).getVersion();
                // First user commit
                String text1 = "foo\nbar";
                FileData save1 = repository1.save(createFileData(filePath, text1), IOUtils.toInputStream(text1));
                theirCommit = save1.getVersion();

                // Second user commit (our). Will merge with first user's change (their).
                String text2 = "foo\nbaz";
                repository2.save(createFileData(filePath, text2), IOUtils.toInputStream(text2));

                fail("MergeConflictException is expected");
            } catch (MergeConflictException e) {
                Collection<String> conflictedFiles = e.getConflictedFiles();

                assertEquals(1, conflictedFiles.size());
                assertEquals(filePath, conflictedFiles.iterator().next());

                assertEquals(baseCommit, e.getBaseCommit());
                assertEquals(theirCommit, e.getTheirCommit());
                assertNotNull(e.getYourCommit());

                // Check that their changes are still present in repository.
                assertEquals("Their changes were reverted in local repository",
                    theirCommit,
                    repository2.check(filePath).getVersion());

                assertNotEquals("Our conflicted commit must be reverted but it exists.",
                    e.getYourCommit(),
                    repository2.check(filePath).getVersion());

                String text2 = "foo\nbaz";
                String resolveText = "foo\nbar\nbaz";
                String mergeMessage = "Merge with " + theirCommit;

                List<FileItem> resolveConflicts = Collections
                    .singletonList(new FileItem(filePath, IOUtils.toInputStream(resolveText)));

                FileData fileData = createFileData(filePath, text2);
                fileData.setVersion(baseCommit);
                fileData.addAdditionalData(new ConflictResolveData(e.getTheirCommit(), resolveConflicts, mergeMessage));
                FileData localData = repository2.save(fileData, IOUtils.toInputStream(text2));

                FileItem remoteItem = repository2.read(filePath);
                assertEquals(resolveText, IOUtils.toStringAndClose(remoteItem.getStream()));
                FileData remoteData = remoteItem.getData();
                assertEquals(localData.getVersion(), remoteData.getVersion());
                assertEquals("John Smith", remoteData.getAuthor());
                assertEquals(mergeMessage, remoteData.getComment());

                // User modifies a file based on old version (baseCommit) and gets conflict.
                // Expected: after conflict their conflicting changes in local repository are not reverted.
                try {
                    String text3 = "test\nbaz";
                    FileData fileData3 = createFileData(filePath, text3);
                    fileData3.setVersion(baseCommit); // It's is needed for this scenario
                    repository2.save(fileData3, IOUtils.toInputStream(text3));
                    fail("MergeConflictException is expected");
                } catch (MergeConflictException ex) {
                    // Check that their changes are still present in repository.
                    assertEquals("Their changes were reverted in local repository",
                        localData.getVersion(),
                        repository2.check(filePath).getVersion());
                }
            }
        }
    }

    @Test
    public void mergeConflictInFileMultipleProjects() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        String baseCommit = null;
        String theirCommit = null;

        final String filePath = "rules/project1/file2";

        try (GitRepository repository1 = createRepository(remote, local1);
                GitRepository repository2 = createRepository(remote, local2)) {
            baseCommit = repository1.check(filePath).getVersion();
            // First user commit
            String text1 = "foo\nbar";
            FileData save1 = repository1.save(createFileData(filePath, text1), IOUtils.toInputStream(text1));
            theirCommit = save1.getVersion();

            // Second user commit (our). Will merge with first user's change (their).
            String text2 = "foo\nbaz";
            FileData fileData = createFileData(filePath, text2);
            InputStream stream = IOUtils.toInputStream(text2);
            repository2.save(Collections.singletonList(new FileItem(fileData, stream)));

            fail("MergeConflictException is expected");
        } catch (MergeConflictException e) {
            Collection<String> conflictedFiles = e.getConflictedFiles();

            assertEquals(1, conflictedFiles.size());
            assertEquals(filePath, conflictedFiles.iterator().next());

            assertEquals(baseCommit, e.getBaseCommit());
            assertEquals(theirCommit, e.getTheirCommit());
            assertNotNull(e.getYourCommit());

            try (GitRepository repository2 = createRepository(remote, local2)) {
                assertNotEquals("Our conflicted commit must be reverted but it exists.",
                    e.getYourCommit(),
                    repository2.check(filePath).getVersion());
            }
        }
    }

    @Test
    public void mergeConflictInFolder() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        String baseCommit = null;
        String theirCommit = null;

        final String folderPath = "rules/project1";

        final String conflictedFile = "rules/project1/file2";
        try (GitRepository repository1 = createRepository(remote, local1);
                GitRepository repository2 = createRepository(remote, local2)) {
            try {
                baseCommit = repository1.check(folderPath).getVersion();
                // First user commit
                String text1 = "foo\nbar";
                List<FileItem> changes1 = Arrays.asList(
                    new FileItem("rules/project1/file1", IOUtils.toInputStream("Modified")),
                    new FileItem("rules/project1/new-path/file4", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, IOUtils.toInputStream(text1)));

                FileData folderData1 = new FileData();
                folderData1.setName("rules/project1");
                folderData1.setAuthor("John Smith");
                folderData1.setComment("Bulk change by John");

                FileData save1 = repository1.save(folderData1, changes1, ChangesetType.DIFF);
                theirCommit = save1.getVersion();

                // Second user commit (our). Will merge with first user's change (their).
                String text2 = "foo\nbaz";
                List<FileItem> changes2 = Arrays.asList(
                    new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, IOUtils.toInputStream(text2)));

                FileData folderData2 = new FileData();
                folderData2.setName("rules/project1");
                folderData2.setAuthor("Jane Smith");
                folderData2.setComment("Bulk change by Jane");
                repository2.save(folderData2, changes2, ChangesetType.DIFF);

                fail("MergeConflictException is expected");
            } catch (MergeConflictException e) {
                Collection<String> conflictedFiles = e.getConflictedFiles();

                assertEquals(1, conflictedFiles.size());
                assertEquals(conflictedFile, conflictedFiles.iterator().next());

                assertEquals(baseCommit, e.getBaseCommit());
                assertEquals(theirCommit, e.getTheirCommit());
                assertNotNull(e.getYourCommit());

                // Check that their changes are still present in repository.
                assertEquals("Their changes were reverted in local repository",
                    theirCommit,
                    repository2.check(conflictedFile).getVersion());

                assertNotEquals("Our conflicted commit must be reverted but it exists.",
                    e.getYourCommit(),
                    repository2.check(conflictedFile).getVersion());

                String text2 = "foo\nbaz";
                String resolveText = "foo\nbar\nbaz";
                String mergeMessage = "Merge with " + theirCommit;

                List<FileItem> changes2 = Arrays.asList(
                    new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, IOUtils.toInputStream(text2)));

                List<FileItem> resolveConflicts = Collections
                    .singletonList(new FileItem(conflictedFile, IOUtils.toInputStream(resolveText)));

                FileData folderData2 = new FileData();
                folderData2.setName("rules/project1");
                folderData2.setAuthor("Jane Smith");
                folderData2.setComment("Bulk change by Jane");
                folderData2.setVersion(baseCommit);
                folderData2
                    .addAdditionalData(new ConflictResolveData(e.getTheirCommit(), resolveConflicts, mergeMessage));
                FileData localData = repository2.save(folderData2, changes2, ChangesetType.DIFF);

                FileItem remoteItem = repository2.read(conflictedFile);
                assertEquals(resolveText, IOUtils.toStringAndClose(remoteItem.getStream()));
                FileData remoteData = remoteItem.getData();
                assertEquals(localData.getVersion(), remoteData.getVersion());
                assertEquals("Jane Smith", remoteData.getAuthor());
                assertEquals(mergeMessage, remoteData.getComment());

                String file1Content = IOUtils.toStringAndClose(repository2.read("rules/project1/file1").getStream());
                assertEquals("Other user's non-conflicting modification is absent.", "Modified", file1Content);

                // User modifies a file based on old version (baseCommit) and gets conflict.
                // Expected: after conflict their conflicting changes in local repository are not reverted.
                try {
                    String text3 = "test\nbaz";
                    List<FileItem> changes3 = Arrays.asList(
                        new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                        new FileItem(conflictedFile, IOUtils.toInputStream(text3)));

                    FileData folderData3 = new FileData();
                    folderData3.setName("rules/project1");
                    folderData3.setAuthor("Jane Smith");
                    folderData3.setComment("Bulk change by Jane");
                    folderData3.setVersion(baseCommit); // It's is needed for this scenario
                    repository2.save(folderData3, changes3, ChangesetType.DIFF);
                    fail("MergeConflictException is expected");
                } catch (MergeConflictException ex) {
                    // Check that their changes are still present in repository.
                    assertEquals("Their changes were reverted in local repository",
                        localData.getVersion(),
                        repository2.check(conflictedFile).getVersion());
                }
            }
        }
    }

    @Test
    public void mergeConflictInFolderWithFileDeleting() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        String baseCommit = null;
        String theirCommit = null;

        final String folderPath = "rules/project1";

        final String conflictedFile = "rules/project1/file2";
        try (GitRepository repository1 = createRepository(remote, local1);
                GitRepository repository2 = createRepository(remote, local2)) {
            try {
                baseCommit = repository1.check(folderPath).getVersion();
                // First user commit
                String text1 = "foo\nbar";
                List<FileItem> changes1 = Arrays.asList(
                    new FileItem("rules/project1/file1", IOUtils.toInputStream("Modified")),
                    new FileItem("rules/project1/new-path/file4", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, IOUtils.toInputStream(text1)));

                FileData folderData1 = new FileData();
                folderData1.setName("rules/project1");
                folderData1.setAuthor("John Smith");
                folderData1.setComment("Bulk change by John");

                FileData save1 = repository1.save(folderData1, changes1, ChangesetType.DIFF);
                theirCommit = save1.getVersion();

                // Second user commit (our). Will merge with first user's change (their).
                List<FileItem> changes2 = Arrays.asList(
                    new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, null));

                FileData folderData2 = new FileData();
                folderData2.setName("rules/project1");
                folderData2.setAuthor("Jane Smith");
                folderData2.setComment("Bulk change by Jane");
                repository2.save(folderData2, changes2, ChangesetType.DIFF);

                fail("MergeConflictException is expected");
            } catch (MergeConflictException e) {
                Collection<String> conflictedFiles = e.getConflictedFiles();

                assertEquals(1, conflictedFiles.size());
                assertEquals(conflictedFile, conflictedFiles.iterator().next());

                assertEquals(baseCommit, e.getBaseCommit());
                assertEquals(theirCommit, e.getTheirCommit());
                assertNotNull(e.getYourCommit());

                // Check that their changes are still present in repository.
                assertEquals("Their changes were reverted in local repository",
                    theirCommit,
                    repository2.check(conflictedFile).getVersion());

                assertNotEquals("Our conflicted commit must be reverted but it exists.",
                    e.getYourCommit(),
                    repository2.check(conflictedFile).getVersion());

                String mergeMessage = "Merge with " + theirCommit;

                List<FileItem> changes2 = Arrays.asList(
                    new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                    new FileItem(conflictedFile, null));

                List<FileItem> resolveConflicts = Collections.singletonList(new FileItem(conflictedFile, null));

                FileData folderData2 = new FileData();
                folderData2.setName("rules/project1");
                folderData2.setAuthor("Jane Smith");
                folderData2.setComment("Bulk change by Jane");
                folderData2.setVersion(baseCommit);
                folderData2
                    .addAdditionalData(new ConflictResolveData(e.getTheirCommit(), resolveConflicts, mergeMessage));
                repository2.save(folderData2, changes2, ChangesetType.DIFF);

                FileItem remoteItem = repository2.read(conflictedFile);
                assertNull(remoteItem);
            }
        }
    }

    @Test
    public void mergeConflictInFolderMultipleProjects() throws IOException {
        // Prepare the test: clone master branch
        File remote = new File(root, "remote");
        File local1 = new File(root, "temp1");
        File local2 = new File(root, "temp2");

        String baseCommit = null;
        String theirCommit = null;

        final String folderPath = "rules/project1";

        final String conflictedFile = "rules/project1/file2";
        try (GitRepository repository1 = createRepository(remote, local1);
                GitRepository repository2 = createRepository(remote, local2)) {
            baseCommit = repository1.check(folderPath).getVersion();
            // First user commit
            String text1 = "foo\nbar";
            List<FileItem> changes1 = Arrays.asList(
                new FileItem("rules/project1/file1", IOUtils.toInputStream("Modified")),
                new FileItem("rules/project1/new-path/file4", IOUtils.toInputStream("Added")),
                new FileItem(conflictedFile, IOUtils.toInputStream(text1)));

            FileData folderData1 = new FileData();
            folderData1.setName("rules/project1");
            folderData1.setAuthor("John Smith");
            folderData1.setComment("Bulk change by John");

            FileData save1 = repository1.save(folderData1, changes1, ChangesetType.DIFF);
            theirCommit = save1.getVersion();

            // Second user commit (our). Will merge with first user's change (their).
            String text2 = "foo\nbaz";
            List<FileItem> changes2 = Arrays.asList(
                new FileItem("rules/project1/new-path/file5", IOUtils.toInputStream("Added")),
                new FileItem(conflictedFile, IOUtils.toInputStream(text2)));

            FileData folderData2 = new FileData();
            folderData2.setName("rules/project1");
            folderData2.setAuthor("Jane Smith");
            folderData2.setComment("Bulk change by Jane");
            FolderItem folderItem = new FolderItem(folderData2, changes2);
            repository2.save(Collections.singletonList(folderItem), ChangesetType.DIFF);

            fail("MergeConflictException is expected");
        } catch (MergeConflictException e) {
            Collection<String> conflictedFiles = e.getConflictedFiles();

            assertEquals(1, conflictedFiles.size());
            assertEquals(conflictedFile, conflictedFiles.iterator().next());

            assertEquals(baseCommit, e.getBaseCommit());
            assertEquals(theirCommit, e.getTheirCommit());
            assertNotNull(e.getYourCommit());

            try (GitRepository repository2 = createRepository(remote, local2)) {
                assertNotEquals("Our conflicted commit must be reverted but it exists.",
                    e.getYourCommit(),
                    repository2.check(conflictedFile).getVersion());
            }
        }
    }

    @Test
    public void testBranches() throws IOException {
        repo.createBranch(FOLDER_IN_REPOSITORY, "project1/test1");
        repo.createBranch(FOLDER_IN_REPOSITORY, "project1/test2");
        List<String> branches = repo.getBranches(FOLDER_IN_REPOSITORY);
        assertTrue(branches.contains("test"));
        assertTrue(branches.contains("project1/test1"));
        assertTrue(branches.contains("project1/test2"));

        // Don't close "project1/test1" and "project1/test2" repositories explicitly.
        // Secondary repositories should be closed by parent repository automatically.
        BranchRepository repoTest1 = repo.forBranch("project1/test1");
        BranchRepository repoTest2 = repo.forBranch("project1/test2");

        assertEquals(BRANCH, repo.getBranch());
        assertEquals("project1/test1", repoTest1.getBranch());
        assertEquals("project1/test2", repoTest2.getBranch());

        repoTest1.deleteBranch(FOLDER_IN_REPOSITORY, "project1/test1");
        branches = repo.getBranches(FOLDER_IN_REPOSITORY);
        assertTrue(branches.contains("test"));
        assertFalse(branches.contains("project1/test1"));
        assertTrue(branches.contains("project1/test2"));

        // Test that forBranch() fetches new branch if it has not been cloned before
        File remote = new File(root, "remote");
        File temp = new File(root, "temp");
        try (GitRepository repository = createRepository(remote, temp, Constants.MASTER)) {
            GitRepository branchRepo = repository.forBranch("project1/test2");
            assertNotNull(branchRepo.check("rules/project1/file1"));
        }
    }

    @Test
    public void pathToRepoInsteadOfUri() {
        File local = new File(root, "local");
        // Will use this path instead of uri. Git accepts that.
        String remote = new File(root, "remote").getAbsolutePath();

        try (GitRepository repository = createRepository(remote, local, BRANCH)) {
            assertNotNull(repository);
        }
        try (GitRepository repository = createRepository(remote + "/", local, BRANCH)) {
            assertNotNull(repository);
        }
        try (GitRepository repository = createRepository(new File(remote).toURI().toString(), local, BRANCH)) {
            assertNotNull(repository);
        }
    }

    @Test
    public void testIsValidBranchName() {
        assertTrue(repo.isValidBranchName("123"));
        assertFalse(repo.isValidBranchName("[~COM1/NUL]"));
    }

    @Test
    public void testFetchChanges() throws IOException, GitAPIException {
        ObjectId before = repo.getLastRevision();
        String newBranch = "new-branch";

        // Make a copy before any modifications
        File local2 = new File(root, "local2");
        FileUtils.copy(new File(root, "local"), local2);

        // Modify on remote
        File remote = new File(root, "remote");
        try (Git git = Git.open(remote)) {
            git.checkout().setName(BRANCH).call();
            git.branchCreate().setName(newBranch).call();

            Repository repository = git.getRepository();

            File rulesFolder = new File(repository.getDirectory().getParentFile(), FOLDER_IN_REPOSITORY);
            File file2 = new File(rulesFolder, "file2");
            writeText(file2, "Modify on remote server");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit()
                .setAll(true)
                .setMessage("Second modification")
                .setCommitter("user2", "user2@gmail.to")
                .call();
            // Fetch must not fail if some tag is added.
            addTag(git, commit, 42);
        }

        // Force fetching
        ObjectId after = repo.getLastRevision();
        assertNotEquals("Last revision should be changed because of a new commit on a server", before, after);
        assertTrue("Branch " + newBranch + " must be created", repo.getAvailableBranches().contains(newBranch));

        // Check that changes are fetched and fast forwarded after getLastRevision()
        List<FileData> file2History = repo.listHistory("rules/project1/file2");
        assertEquals(3, file2History.size());

        // Check that after repo initialization all changes are fetched and fast forwarded
        try (GitRepository repo2 = createRepository(new File(root, "remote"), local2)) {
            file2History = repo2.listHistory("rules/project1/file2");
            assertEquals(3, file2History.size());
            assertTrue("Branch " + newBranch + " must be created", repo2.getAvailableBranches().contains(newBranch));
        }

        // Check that all branches are available when repository is cloned.
        try (GitRepository repo3 = createRepository(new File(root, "remote"), new File(root, "local3"))) {
            assertTrue("Branch " + newBranch + " must be created", repo3.getAvailableBranches().contains(newBranch));
        }

        // Delete a branch on remote repository
        try (Git git = Git.open(remote)) {
            git.checkout().setName(Constants.MASTER).call();
            git.branchDelete().setBranchNames(BRANCH).setForce(true).call();
        }

        // Force fetching
        repo.getLastRevision();
        assertFalse("Branch " + BRANCH + " must be deleted", repo.getAvailableBranches().contains(BRANCH));

        // Check that after repo initialization the branch is deleted on local repository.
        try (GitRepository repo2 = createRepository(new File(root, "remote"), local2, "master")) {
            assertFalse("Branch " + BRANCH + " must be deleted", repo2.getAvailableBranches().contains(BRANCH));
        }
    }

    @Test
    public void testPullDoesntAutoMerge() throws IOException {
        final String newBranch = "new-branch";
        repo.createBranch(FOLDER_IN_REPOSITORY, newBranch);
        GitRepository newBranchRepo = repo.forBranch(newBranch);

        // Add a new commit in the new branch.
        final String newPath = "rules/project1/folder/file-in-new-branch";
        String newText = "File located in " + newPath;
        newBranchRepo.save(createFileData(newPath, newText), IOUtils.toInputStream(newText));

        // Add a new commit in 'test' branch after 'new-branch' was created. Forces invocation of 'git checkout test' to
        // switch branch.
        String mainText = "Modify";
        repo.save(createFileData("rules/project1/folder/file4", mainText), IOUtils.toInputStream(mainText));

        // After current branch was switched to 'test', invoke pull on 'new-branch'.
        newBranchRepo.pull("John Smith");

        assertNotNull("The file '" + newPath + "' must exist in '" + newBranch + "'", newBranchRepo.check(newPath));
        // Check that pull is invoked on correct branch and that 'new-branch' isn't merged into 'test'.
        assertNull(
            "The file '" + newPath + "' must be absent in '" + BRANCH + "', because the branch '" + newBranch + "' wasn't merged yet.",
            repo.check(newPath));
    }

    @Test
    public void testOnlySpecifiedBranchesAreMerged() throws IOException {
        final String branch1 = "branch1";
        repo.createBranch(FOLDER_IN_REPOSITORY, branch1);
        GitRepository branch1Repo = repo.forBranch(branch1);

        final String branch2 = "branch2";
        repo.createBranch(FOLDER_IN_REPOSITORY, branch2);
        GitRepository branch2Repo = repo.forBranch(branch2);

        // Add commits in the new branches.
        final String path1 = "rules/project1/folder/new-file1";
        String text1 = "Text1";
        branch1Repo.save(createFileData(path1, text1), IOUtils.toInputStream(text1));

        final String path2 = "rules/project1/folder/new-file2";
        String text2 = "Text2";
        branch2Repo.save(createFileData(path2, text2), IOUtils.toInputStream(text2));

        // Add a new commit in 'test' branch after new branches were created. Forces invocation of 'git checkout test'
        // to switch branch.
        String mainText = "Modify";
        repo.save(createFileData("rules/project1/folder/file4", mainText), IOUtils.toInputStream(mainText));

        // After current branch was switched to 'test', merge 'branch1' to 'branch2'.
        branch2Repo.merge(branch1, "John Smith", null);

        // Check that 'branch1' and 'branch2' aren't merged into 'test'
        assertNull(
            "The file '" + path1 + "' must be absent in '" + BRANCH + "', because the branch '" + branch1 + "' wasn't merged yet.",
            repo.check(path1));
        assertNull(
            "The file '" + path2 + "' must be absent in '" + BRANCH + "', because the branch '" + branch2 + "' wasn't merged yet.",
            repo.check(path2));

        // Check that ''branch2' isn't merged into 'branch1'
        assertNotNull("The file '" + path1 + "' must exist in '" + branch1 + "'", branch1Repo.check(path1));
        assertNull("The file '" + path2 + "' must be absent in '" + branch1 + "'", branch1Repo.check(path2));

        // Check that 'branch1 is merged into 'branch2'
        assertNotNull("The file '" + path1 + "' must exist in '" + branch2 + "'", branch2Repo.check(path1));
        assertNotNull("The file '" + path2 + "' must exist in '" + branch2 + "'", branch2Repo.check(path2));
    }

    @Test
    public void testResetUncommittedChanges() throws IOException {
        File parent;
        try (Git git = repo.getClosableGit()) {
            parent = git.getRepository().getDirectory().getParentFile();
        }
        File existingFile = new File(parent, "file-in-master");
        assertTrue(existingFile.exists());

        // Delete the file but don't commit it. Changes in not committed (modified externally for example or after
        // unsuccessful operation)
        // files must be aborted after repo.save() method.
        FileUtils.delete(existingFile);
        assertFalse(existingFile.exists());

        // Save other file.
        String text = "Some text";
        repo.save(createFileData("folder/any-file", text), IOUtils.toInputStream(text));

        // Not committed changes should be aborted
        assertTrue(existingFile.exists());
    }

    @Test
    public void testURIIdentity() throws URISyntaxException {
        URI a = new URI("https://github.com/openl-tablets/openl-tablets.git");
        URI b = new URI("https://github.com/openl-tablets/openl-tablets.git");
        assertEquals(a, b);
        assertTrue(GitRepository.isSame(a, b));

        b = new URI("http://github.com/openl-tablets/openl-tablets.git/");
        assertNotEquals(a, b);
        assertTrue(GitRepository.isSame(a, b));

        b = new URI("http://github.com/openl-tablets/openl-tablets.git?a=foo&b=bar");
        assertNotEquals(a, b);
        assertFalse(GitRepository.isSame(a, b));
    }

    private GitRepository createRepository(File remote, File local) {
        return createRepository(remote, local, BRANCH);
    }

    private GitRepository createRepository(File remote, File local, String branch) {
        return createRepository(remote.toURI().toString(), local, branch);
    }

    private GitRepository createRepository(String remoteUri, File local, String branch) {
        GitRepository repo = new GitRepository();
        repo.setUri(remoteUri);
        repo.setLocalRepositoryPath(local.getAbsolutePath());
        repo.setBranch(branch);
        repo.setTagPrefix(TAG_PREFIX);
        repo.setCommentTemplate("WebStudio: {commit-type}. {user-message}");
        String settingsPath = local.getParent() + "/git-settings";
        FileSystemRepository settingsRepository = new FileSystemRepository();
        settingsRepository.setUri(settingsPath);
        String locksRoot = new File(root, "locks").getAbsolutePath();
        repo.setRepositorySettings(new RepositorySettings(settingsRepository, locksRoot, 1));
        repo.setGcAutoDetach(false);
        repo.initialize();

        return repo;
    }

    private FileData getFileData(List<FileData> files, String fileName) {
        for (FileData fileData : files) {
            if (fileName.equals(fileData.getName())) {
                return fileData;
            }
        }
        return null;
    }

    private static void addTag(Git git, RevCommit commit, int version) throws GitAPIException {
        git.tag().setObjectId(commit).setName(TAG_PREFIX + version).call();
    }

    private FileData find(List<FileData> files, String fileName) {
        for (FileData file : files) {
            if (fileName.equals(file.getName())) {
                return file;
            }
        }

        throw new IllegalArgumentException(String.format("File '%s' is not found.", fileName));
    }

    private static class ChangesCounter implements Listener {
        private int changes = 0;

        @Override
        public void onChange() {
            changes++;
        }

        int getChanges() {
            return changes;
        }
    }
}