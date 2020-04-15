package org.openl.rules.webstudio.web.repository;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.SessionScoped;

import org.openl.rules.webstudio.web.repository.cache.ProjectVersionCacheManager;
import org.openl.rules.webstudio.web.repository.tree.TreeNode;
import org.openl.rules.webstudio.web.repository.tree.TreeProductProject;
import org.openl.rules.webstudio.web.util.WebStudioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedBean
@SessionScoped
public class ProductionRepositoriesTreeController {

    private final Logger log = LoggerFactory.getLogger(ProductionRepositoriesTreeController.class);

    @ManagedProperty(value = "#{repositorySelectNodeStateHolder}")
    private RepositorySelectNodeStateHolder repositorySelectNodeStateHolder;

    @ManagedProperty(value = "#{productionRepositoriesTreeState}")
    private ProductionRepositoriesTreeState productionRepositoriesTreeState;

    @ManagedProperty(value = "#{projectVersionCacheManager}")
    private ProjectVersionCacheManager projectVersionCacheManager;

    public ProductionRepositoriesTreeState getProductionRepositoriesTreeState() {
        return productionRepositoriesTreeState;
    }

    public void setProductionRepositoriesTreeState(ProductionRepositoriesTreeState productionRepositoriesTreeState) {
        this.productionRepositoriesTreeState = productionRepositoriesTreeState;
    }

    /**
     * Gets all rules projects from a rule repository.
     *
     * @return list of rules projects
     */
    public List<TreeNode> getRulesProjects() {
        TreeNode selectedNode = repositorySelectNodeStateHolder.getSelectedNode();
        return selectedNode == null ? Collections.<TreeNode> emptyList() : selectedNode.getChildNodes();
    }

    public String selectRulesProject() {
        String projectName = WebStudioUtils.getRequestParameter("projectName");

        TreeNode selectedNode = repositorySelectNodeStateHolder.getSelectedNode();
        if (selectedNode == null) {
            return null;
        }
        if (selectedNode.getType().equals(UiConst.TYPE_PRODUCTION_REPOSITORY) || selectedNode.getType()
            .equals(UiConst.TYPE_PRODUCTION_DEPLOYMENT_PROJECT)) {
            for (TreeNode node : selectedNode.getChildNodes()) {
                if (node.getName().equals(projectName)) {
                    repositorySelectNodeStateHolder.setSelectedNode(node);
                    break;
                }
            }
        }

        return null;
    }

    public void openTab() {
        productionRepositoriesTreeState.initTree();

        TreeNode node = productionRepositoriesTreeState.getFirstProductionRepo();
        if (node != null) {
            repositorySelectNodeStateHolder.setSelectedNode(node);
        }
    }

    public RepositorySelectNodeStateHolder getRepositorySelectNodeStateHolder() {
        return repositorySelectNodeStateHolder;
    }

    public void setRepositorySelectNodeStateHolder(RepositorySelectNodeStateHolder repositorySelectNodeStateHolder) {
        this.repositorySelectNodeStateHolder = repositorySelectNodeStateHolder;
    }

    public void setProjectVersionCacheManager(ProjectVersionCacheManager projectVersionCacheManager) {
        this.projectVersionCacheManager = projectVersionCacheManager;
    }

    public String refreshTree() {
        productionRepositoriesTreeState.invalidateTree();

        return null;
    }

    public void refreshInitTree() {
        productionRepositoriesTreeState.invalidateTree();
        productionRepositoriesTreeState.initTree();
    }

    public void deleteProdRepo(String configName) {
        if (productionRepositoriesTreeState.getRoot() != null) {
            productionRepositoriesTreeState.getRoot().removeChild(configName);
        }
    }

    public String getBusinessVersion(TreeProductProject version) {
        try {
            String businessVersion = projectVersionCacheManager
                .getDesignBusinessVersionOfDeployedProject(version.getData().getProject());
            return businessVersion != null ? businessVersion : "No valid revision found";
        } catch (IOException e) {
            log.error("Error during getting project design version", e);
            return "No valid revision found";
        }
    }
}
