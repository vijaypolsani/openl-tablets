package org.openl.rules.repository.jcr;

import java.io.InputStream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.openl.rules.common.ProjectException;
import org.openl.rules.common.impl.ArtefactPathImpl;
import org.openl.rules.repository.api.ArtefactAPI;
import org.openl.rules.repository.api.FolderAPI;
import org.openl.rules.repository.api.ResourceAPI;
import org.openl.rules.repository.exceptions.RRepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseJcrRepository {
    private final Logger log = LoggerFactory.getLogger(BaseJcrRepository.class);
    /**
     * JCR Session
     */
    private final Session session;

    public BaseJcrRepository(Session session) {
        this.session = session;
    }

    protected Node checkFolder(String aPath) throws RepositoryException, ProjectException {
        Node node = session.getRootNode();
        String[] paths = aPath.split("/");
        String currentPath = "";
        for (String path : paths) {
            if (path.length() == 0) {
                continue; // first element (root folder) or illegal path
            }

            if (node.hasNode(path)) {
                // go deeper
                node = node.getNode(path);
            } else {
                // create new
                JcrFolderAPI folder = new JcrFolderAPI(node, new ArtefactPathImpl(currentPath));
                node = folder.addFolder(path).node();
            }

            if (!currentPath.isEmpty()) {
                currentPath += "/";
            }
            currentPath += path;
        }

        return node;
    }

    protected Node findNode(String aPath) throws RepositoryException {
        Node node = session.getRootNode();
        String[] paths = aPath.split("/");
        for (String path : paths) {
            if (path.length() == 0) {
                continue; // first element (root folder) or illegal path
            }

            if (node.hasNode(path)) {
                // go deeper
                node = node.getNode(path);
            } else {
                return null;
            }
        }

        return node;
    }

    protected Session getSession() {
        return session;
    }

    public ArtefactAPI getArtefact(String name) throws RRepositoryException {
        try {
            Node node = findNode(name);
            if (node == null) {
                return null;
            }

            return createArtefactAPI(node, name);
        } catch (RepositoryException e) {
            log.debug("Cannot get artefact " + name, e);
            return null;
        }
    }

    public ResourceAPI createResource(String name, InputStream inputStream) throws RRepositoryException {
        try {
            Node node = checkFolder(name.substring(0, name.lastIndexOf("/")));
            ArtefactAPI artefact = createArtefactAPI(node, name);
            if (!(artefact instanceof FolderAPI)) {
                throw new RepositoryException("Incorrect node type");
            }

            FolderAPI folder = (FolderAPI) artefact;
            return folder.addResource(name.substring(name.lastIndexOf("/") + 1), inputStream);
        } catch (RepositoryException e) {
            throw new RRepositoryException("Cannot add resource " + name, e);
        } catch (ProjectException e) {
            throw new RRepositoryException("Cannot add resource " + name, e);
        }
    }

    private ArtefactAPI createArtefactAPI(Node node, String name) throws RepositoryException {
        if (node.isNodeType(JcrNT.NT_LOCK)) {
            log.error("Incorrect node type " + JcrNT.NT_LOCK);
            return null;
        } else {
            ArtefactPathImpl path = new ArtefactPathImpl(name.split("/"));
            if (node.isNodeType(JcrNT.NT_FILE)) {
                return new JcrFileAPI(node, path);
            } else {
                return new JcrFolderAPI(node, path);
            }
        }
    }

    public ArtefactAPI rename(String path, String destination) throws RRepositoryException {
        try {
            session.move(path, destination);
            return getArtefact(destination);
        } catch (RepositoryException e) {
            throw new RRepositoryException(e.getMessage(), e);
        }
    }
}
