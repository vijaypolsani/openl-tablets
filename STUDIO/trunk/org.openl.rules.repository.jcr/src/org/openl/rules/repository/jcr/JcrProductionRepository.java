package org.openl.rules.repository.jcr;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openl.rules.common.ArtefactPath;
import org.openl.rules.common.impl.ArtefactPathImpl;
import org.openl.rules.repository.RDeploymentDescriptorProject;
import org.openl.rules.repository.RProductionDeployment;
import org.openl.rules.repository.RProductionRepository;
import org.openl.rules.repository.RProject;
import org.openl.rules.repository.RDeploymentListener;
import org.openl.rules.repository.api.ArtefactAPI;
import org.openl.rules.repository.api.FolderAPI;
import org.openl.rules.repository.api.ArtefactProperties;
import org.openl.rules.repository.exceptions.RRepositoryException;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.EventIterator;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class JcrProductionRepository extends BaseJcrRepository implements RProductionRepository, EventListener {
    private static final Log log = LogFactory.getLog(JcrProductionRepository.class);

    public static class JCR_SQL2QueryBuilder{
        private boolean firstCondition = true;

        private void appendDateCondition(String propertyName, Date date, String condition, StringBuilder sb) {
            if (date != null) {
                if(firstCondition){
                    firstCondition = false;
                    sb.append(" WHERE ");
                }else{
                    sb.append(" AND ");
                }
                sb.append('[').append(propertyName).append(']').append(condition).append(date.getTime());
            }
        }

        public String buildQuery(SearchParams params) {
            StringBuilder sb = new StringBuilder("SELECT * FROM [nt:base]");
            if (!StringUtils.isEmpty(params.getLineOfBusiness())) {
                if(firstCondition){
                    firstCondition = false;
                    sb.append(" WHERE ");
                }else{
                    sb.append(" AND ");
                }
                // todo: check for injection
                sb.append("[" + ArtefactProperties.PROP_LINE_OF_BUSINESS + "]").append("=\"").append(params.getLineOfBusiness()).append("\"");
            }

            appendDateCondition(ArtefactProperties.PROP_EFFECTIVE_DATE, params.getLowerEffectiveDate(), " >= ", sb);
            appendDateCondition(ArtefactProperties.PROP_EFFECTIVE_DATE, params.getUpperEffectiveDate(), " <= ", sb);
            appendDateCondition(ArtefactProperties.PROP_EXPIRATION_DATE, params.getLowerExpirationDate(), " >= ", sb);
            appendDateCondition(ArtefactProperties.PROP_EXPIRATION_DATE, params.getUpperExpirationDate(), " <= ", sb);

            return sb.toString();
        }
    }

    final static String PROPERTY_NOTIFICATION = "deploymentReady";
    public static final String DEPLOY_ROOT = "/deploy";

    private Node deployLocation;
    private List<RDeploymentListener> listeners = new ArrayList<RDeploymentListener>();

    public JcrProductionRepository(String name, Session session) throws RepositoryException {
        super(name, session);

        deployLocation = checkPath(DEPLOY_ROOT);
        if (deployLocation.isNew()) {
            session.save();
        }

        session.getWorkspace().getObservationManager().addEventListener(this, Event.PROPERTY_ADDED, DEPLOY_ROOT, false,
                null, null, false);
    }

    public synchronized void addListener(RDeploymentListener listener) throws RRepositoryException {
        listeners.add(listener);
    }

    @Deprecated
    public RDeploymentDescriptorProject createDDProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public RProductionDeployment createDeployment(String name) throws RRepositoryException {
        try {
            return JcrProductionDeployment.createDeployment(deployLocation, name);
        } catch (RepositoryException e) {
            throw new RRepositoryException("could not create deployment {0}", e, name);
        }
    }

    /**
     * Creates a project in the repository. Name of new project must be unique.
     *
     * @param name name of new project
     * @return newly created project
     * @throws org.openl.rules.repository.exceptions.RRepositoryException if
     *             failed
     */
    @Deprecated
    public RProject createProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public Collection<ArtefactAPI> findNodes(SearchParams params) throws RRepositoryException {
        try {
            Query query = session.getWorkspace().getQueryManager().createQuery(new JCR_SQL2QueryBuilder().buildQuery(params), Query.JCR_SQL2);
            QueryResult queryResult = query.execute();

            NodeIterator nodeIterator = queryResult.getNodes();
            List<ArtefactAPI> result = new ArrayList<ArtefactAPI>();
            while (nodeIterator.hasNext()) {
                Node node = nodeIterator.nextNode();
                ArtefactPath path = new ArtefactPathImpl(new String[] { node.getName() });
                String type = node.getPrimaryNodeType().getName();
                if (type.equals(JcrNT.NT_APROJECT)) {
                    result.add(new JcrFolderAPI(node, path));
                } else if (type.equals(JcrNT.NT_FOLDER)) {
                    result.add(new JcrFolderAPI(node, path));
                } else if (type.equals(JcrNT.NT_FILE)) {
                    result.add(new JcrFileAPI(node, path, false));
                }
            }

            return result;
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to run query", e);
        }
    }

    @Deprecated
    public RDeploymentDescriptorProject getDDProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public List<RDeploymentDescriptorProject> getDDProjects() throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public RProductionDeployment getDeployment(String name) throws RRepositoryException {
        Node node;
        try {
            node = deployLocation.getNode(name);
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to get node", e);
        }

        try {
            return new JcrProductionDeployment(node);
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to wrap JCR node", e);
        }
    }

    public Collection<String> getDeploymentNames() throws RRepositoryException {
        List<String> result = new ArrayList<String>();
        try {
            NodeIterator iterator = deployLocation.getNodes();
            while (iterator.hasNext()) {
                Node node = iterator.nextNode();
                if (node.getPrimaryNodeType().getName().equals(JcrNT.NT_DEPLOYMENT)) {
                    result.add(node.getName());
                }
            }
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to enumerate deployments", e);
        }

        return result;
    }

    /**
     * Gets project by name.
     *
     * @param name
     * @return project
     * @throws org.openl.rules.repository.exceptions.RRepositoryException if
     *             failed or no project with specified name
     */
    @Deprecated
    public RProject getProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets list of projects from the repository.
     *
     * @return list of projects
     * @throws org.openl.rules.repository.exceptions.RRepositoryException if
     *             failed
     */
    @Deprecated
    public List<RProject> getProjects() throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets list of projects from the repository that are marked for deletion.
     *
     * @return list of projects that are marked for deletion
     */
    @Deprecated
    public List<RProject> getProjects4Deletion() throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public boolean hasDeploymentProject(String name) throws RRepositoryException {
        try {
            return deployLocation.hasNode(name);
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to check project {0}", e, name);
        }
    }

    public boolean hasDeployment(String name) throws RRepositoryException {
        try {
            return deployLocation.hasNode(name);
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to check project {0}", e, name);
        }
    }

    /**
     * Checks whether project with given name exists in the repository.
     *
     * @param name
     * @return <code>true</code> if project with such name exists
     * @throws org.openl.rules.repository.exceptions.RRepositoryException
     *
     */
    public boolean hasProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public void onEvent(EventIterator eventIterator) {
        boolean hasInterestingEvents = false;
        while (eventIterator.hasNext()) {
            Event event = eventIterator.nextEvent();
            try {
                if (event.getPath().equals(DEPLOY_ROOT + "/" + PROPERTY_NOTIFICATION)) {
                    hasInterestingEvents = true;
                    break;
                }
            } catch (RepositoryException e) {
                if (log.isDebugEnabled()) {
                    log.debug("onEvent-1", e);
                }
            }
        }

        if (hasInterestingEvents) {
            Collection<RDeploymentListener> listenersCopy = new ArrayList<RDeploymentListener>(listeners);
            for (RDeploymentListener l : listenersCopy) {
                try {
                    l.projectsAdded();
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("onEvent-2", e);
                    }
                }
            }

        }
    }

    /**
     * Releases resources allocated by this Rules Repository instance.
     */
    @Override
    public void release() {
        try {
            session.getWorkspace().getObservationManager().removeEventListener(this);
        } catch (RepositoryException e) {
            if (log.isDebugEnabled()) {
                log.debug("release", e);
            }
        }
        super.release();
    }

    public synchronized boolean removeListener(RDeploymentListener listener) throws RRepositoryException {
        return listeners.remove(listener);
    }

    public FolderAPI createDeploymentProject(String name) throws RRepositoryException {
        try {
            Node node = NodeUtil.createNode(deployLocation, name, JcrNT.NT_APROJECT, true);
            deployLocation.save();
            node.checkin();
            repositoryNotify();
            return new JcrFolderAPI(node, new ArtefactPathImpl(new String[] { name }));
        } catch (RepositoryException e) {
            throw new  RRepositoryException("",e);
        }
    }

    //FIXME
    private static final Object lock = new Object();
    private void repositoryNotify() throws RepositoryException {
        synchronized (lock) {
            deployLocation.setProperty(JcrProductionRepository.PROPERTY_NOTIFICATION, (String) null);
            deployLocation.setProperty(JcrProductionRepository.PROPERTY_NOTIFICATION, "1");
            deployLocation.save();
        }
    }

    
    public FolderAPI createRulesProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public FolderAPI getDeploymentProject(String name) throws RRepositoryException {
        Node node;
        try {
            node = deployLocation.getNode(name);
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to get node", e);
        }

        try {
            return new JcrFolderAPI(node, new ArtefactPathImpl(new String[] { name }));
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to wrap JCR node", e);
        }
    }

    public List<FolderAPI> getDeploymentProjects() throws RRepositoryException {
        List<FolderAPI> result = new ArrayList<FolderAPI>();
        try {
            NodeIterator iterator = deployLocation.getNodes();
            while (iterator.hasNext()) {
                Node node = iterator.nextNode();
                if (node.getPrimaryNodeType().getName().equals(JcrNT.NT_APROJECT)) {
                    result.add(new JcrFolderAPI(node, new ArtefactPathImpl(new String[] { node.getName() })));
                }
            }
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to enumerate deployments", e);
        }
        return result;
    }

    public FolderAPI getRulesProject(String name) throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public List<FolderAPI> getRulesProjects() throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public List<FolderAPI> getRulesProjectsForDeletion() throws RRepositoryException {
        throw new UnsupportedOperationException();
    }

    public Collection<String> getDeploymentProjectNames() throws RRepositoryException {
        List<String> result = new ArrayList<String>();
        try {
            NodeIterator iterator = deployLocation.getNodes();
            while (iterator.hasNext()) {
                Node node = iterator.nextNode();
                if (node.getPrimaryNodeType().getName().equals(JcrNT.NT_APROJECT)) {
                    result.add(node.getName());
                }
            }
        } catch (RepositoryException e) {
            throw new RRepositoryException("failed to enumerate deployments", e);
        }
        return result;
    }
}
