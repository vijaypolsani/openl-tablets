package org.openl.rules.ui.tablewizard;

import static org.openl.rules.ui.tablewizard.WizardUtils.getMetaInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.faces.validator.ValidatorException;

import org.apache.commons.lang.StringUtils;
import org.hibernate.validator.constraints.NotBlank;
import org.openl.rules.lang.xls.XlsSheetSourceCodeModule;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.table.properties.ITableProperties;
import org.openl.rules.table.properties.def.TablePropertyDefinition;
import org.openl.rules.table.properties.def.TablePropertyDefinitionUtils;
import org.openl.rules.table.properties.inherit.InheritanceLevel;
import org.openl.rules.table.xls.XlsSheetGridModel;
import org.openl.rules.table.xls.builder.CreateTableException;
import org.openl.rules.table.xls.builder.PropertiesTableBuilder;
import org.openl.rules.table.xls.builder.TableBuilder;
import org.openl.rules.tableeditor.renderkit.TableProperty;

public class PropertyTableCreationWizard extends TableCreationWizard {

    private PropertiesBean propertiesManager;

    private String scopeType;
    @NotBlank(message="Can not be empty")
    private String tableName;
    private String categoryName;

    private List<SelectItem> scopeTypes = new ArrayList<SelectItem>(Arrays.asList(new SelectItem("Module"),
            new SelectItem("Category")));

    private String categoryNameSelector = "destination";

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryNameSelector() {
        return categoryNameSelector;
    }

    public void setCategoryNameSelector(String categoryNameSelector) {
        this.categoryNameSelector = categoryNameSelector;
    }

    // Not sure, probably method can be removed  
    public List<SelectItem> getCategoryNamesList() {
        List<SelectItem> categoryList = new ArrayList<SelectItem>();
        Set<String> categories = getAllCategories();
        for (String categoryName : categories) {
            categoryList.add(new SelectItem(
                    // Replace new line by space
                    categoryName.replaceAll("[\r\n]", " ")));
        }
        return categoryList;
    }

    // Not sure, probably method can be removed 
    private Set<String> getAllCategories() {
        Set<String> categories = new TreeSet<String>();
        TableSyntaxNode[] syntaxNodes = getMetaInfo().getXlsModuleNode().getXlsTableSyntaxNodes();
        
        for (TableSyntaxNode node : syntaxNodes) {
            ITableProperties tableProperties = node.getTableProperties();
            if (tableProperties != null) {
                String categoryName = tableProperties.getCategory();
                if (StringUtils.isNotBlank(categoryName)) {
                    categories.add(categoryName);
                }
            }
        }
        return categories;
    }

    public List<SelectItem> getSpecificCategoryNameList() {
        List<SelectItem> specCategoryList = new ArrayList<SelectItem>();
        Set<String> categories = getAllSpecificCategories();

        for (String categoryName : categories) {
            specCategoryList.add(new SelectItem(
                    // Replace new line by space
                    categoryName.replaceAll("[\r\n]", " ")));
        }
        return specCategoryList;
    }
    
    private Set<String> getAllSpecificCategories() {
        Set<String> specificCategories = new TreeSet<String>();
        TableSyntaxNode[] syntaxNodes = getMetaInfo().getXlsModuleNode().getXlsTableSyntaxNodes();

        for (TableSyntaxNode node : syntaxNodes) {

            XlsSheetSourceCodeModule tableModule = node.getXlsSheetSourceCodeModule();
            if (tableModule != null) {
                String categoryName = tableModule.getDisplayName();
                if (StringUtils.isNotBlank(categoryName)) {
                    specificCategories.add(categoryName);
                }
            }
        }
        return specificCategories;
    }

    public PropertiesBean getPropertiesManager() {
        return propertiesManager;
    }

    public List<SelectItem> getScopeTypes() {
        return scopeTypes;
    }

    public void setScopeTypes(List<SelectItem> scopeTypes) {
        this.scopeTypes = scopeTypes;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    @Override
    public String next() {
        if (getStep() == 1){
            // After step two we create PropertiesBean according to specified scope
            propertiesManager = new PropertiesBean(getPropertyNamesList());
        }
        return super.next();
    }

    @Override
    protected void onStart() {
        reset();
        initWorkbooks();
    }

    @Override
    protected void onCancel() {
        reset();
    }

    @Override
    protected void reset() {
        super.reset();
    }

    @Override
    public String getName() {
        return "newTableProperty";
    }

    public List<String> getPropertyNamesList() {
        List<String> propertyNames = new ArrayList<String>();
        TablePropertyDefinition[] propDefinitions = TablePropertyDefinitionUtils
                .getDefaultDefinitionsByInheritanceLevel(InheritanceLevel.valueOf(scopeType.toUpperCase()));
        for (TablePropertyDefinition propDefinition : propDefinitions) {
            String propName = propDefinition.getName();
            List<String> exceptProperties = getExceptProperties();
            if (!exceptProperties.contains(propName)) {
                propertyNames.add(propName);
            }
        }
        return propertyNames;
    }

    private List<String> getExceptProperties() {
        List<String> exceptProps = new ArrayList<String>();
        exceptProps.add("scope");
        exceptProps.add("category");
        return exceptProps;
    }

    @Override
    protected void onFinish() throws Exception {
        XlsSheetSourceCodeModule sheetSourceModule = getDestinationSheet();
        String newTableUri = buildTable(sheetSourceModule);
        setNewTableUri(newTableUri);
        getModifiedWorkbooks().add(sheetSourceModule.getWorkbookSource());
        super.onFinish();
    }

    protected String buildTable(XlsSheetSourceCodeModule sourceCodeModule) throws CreateTableException {
        XlsSheetGridModel gridModel = new XlsSheetGridModel(sourceCodeModule);
        PropertiesTableBuilder builder = new PropertiesTableBuilder(gridModel);
        Map<String, Object> properties = buildProperties();
        builder.beginTable(TableBuilder.HEADER_HEIGHT + properties.size());
        builder.writeHeader(tableName);
        builder.writeBody(properties);

        String uri = gridModel.getRangeUri(builder.getTableRegion());

        builder.endTable();
        return uri;
    }

    private String buildCategoryName() {
        if (categoryNameSelector.equals("specific") && StringUtils.isNotBlank(this.categoryName)) {
            return this.categoryName;
        }
        return null;
    }

    @Override
    protected Map<String, Object> buildProperties() {
        Map<String, Object> resultProperties = new LinkedHashMap<String, Object>();
        resultProperties.put("scope", scopeType);
        if (InheritanceLevel.CATEGORY.getDisplayName().equals(scopeType)) {
            String categoryName = buildCategoryName();
            if (categoryName != null) {
                resultProperties.put("category", categoryName);
            }
        }
        for (TableProperty property : propertiesManager.getProperties()) {
            String name = property.getName();
            Object value = property.getValue();
            if (value == null || (value != null && (value instanceof String && StringUtils.isEmpty((String) value)))) {
                continue;
            } else {
                resultProperties.put(name.trim(), value);
            }
        }
        return resultProperties;
    }
    
    /**
     * Validation for properties name
     */
    public void validatePropsName(FacesContext context, UIComponent toValidate, Object value) {
        FacesMessage message = new FacesMessage();
        String name = ((String) value).toUpperCase();

        if (name.isEmpty()) {
            message.setDetail("Can not be empty");
            throw new ValidatorException(message);
        }

        String pattern = "([a-zA-Z_][a-zA-Z_0-9]*)?";
        if (!name.matches(pattern)) {
            message.setDetail("Invalid name for parameter");
            throw new ValidatorException(message);
        }

        int i = 0;
        int paramId = this.getPropId(toValidate.getClientId());
        for (TableProperty prop : propertiesManager.getProperties()) {
            if (paramId != i && prop.getStringValue() != null && prop.getStringValue().toUpperCase().equals(name)) {
                message.setDetail("Parameter with such name already exists");
                throw new ValidatorException(message);
            }

            i++;
        }
    }

    private int getPropId(String componentId) {
        if(componentId != null) {
            String[] elements = componentId.split(":");
            
            if (elements.length > 3) {
                return Integer.parseInt(elements[2]);
            }
        }
        
        return 0;
    }

}
