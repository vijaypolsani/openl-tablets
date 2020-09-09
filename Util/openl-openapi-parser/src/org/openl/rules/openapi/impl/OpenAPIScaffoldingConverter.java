package org.openl.rules.openapi.impl;

import static org.openl.rules.openapi.impl.OpenAPITypeUtils.OBJECT;
import static org.openl.rules.openapi.impl.OpenAPITypeUtils.getSimpleName;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.getSchemas;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.normalizeName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.jxpath.JXPathContext;
import org.openl.rules.model.scaffolding.DatatypeModel;
import org.openl.rules.model.scaffolding.FieldModel;
import org.openl.rules.model.scaffolding.InputParameter;
import org.openl.rules.model.scaffolding.PathInfo;
import org.openl.rules.model.scaffolding.ProjectModel;
import org.openl.rules.model.scaffolding.SpreadsheetModel;
import org.openl.rules.model.scaffolding.StepModel;
import org.openl.rules.openapi.OpenAPIModelConverter;
import org.openl.util.CollectionUtils;
import org.openl.util.StringUtils;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

public class OpenAPIScaffoldingConverter implements OpenAPIModelConverter {

    public static final String SPREADSHEET_RESULT = "SpreadsheetResult";
    public static final String RESULT = "Result";
    public static final String DEFAULT_RUNTIME_CONTEXT = "DefaultRulesRuntimeContext";
    public static final Pattern ARRAY_MATCHER = Pattern.compile("[\\[\\]]");
    public static final Pattern PARAMETERS_BRACKETS_MATCHER = Pattern.compile("\\{.*?}");

    private boolean generateUnusedModels = true;

    public OpenAPIScaffoldingConverter() {
    }

    public OpenAPIScaffoldingConverter(boolean generateUnusedModels) {
        this.generateUnusedModels = generateUnusedModels;
    }

    @Override
    public ProjectModel extractProjectModel(String pathTo) {
        ParseOptions options = OpenLOpenAPIUtils.getParseOptions();
        OpenAPI openAPI = new OpenAPIV3Parser().read(pathTo, null, options);
        if (openAPI == null) {
            throw new IllegalStateException("Error creating the project, uploaded file has invalid structure.");
        }
        JXPathContext jxPathContext = JXPathContext.newContext(openAPI);
        String projectName = openAPI.getInfo().getTitle();
        Map<String, Integer> allUsedSchemaRefs = OpenLOpenAPIUtils
            .getAllUsedSchemaRefs(openAPI, jxPathContext, OpenLOpenAPIUtils.PathTarget.ALL);
        List<PathInfo> pathInfos = new ArrayList<>();

        Map<String, Integer> allUsedSchemaRefsInRequests = OpenLOpenAPIUtils
            .getAllUsedSchemaRefs(openAPI, jxPathContext, OpenLOpenAPIUtils.PathTarget.REQUESTS);

        Set<String> allUnusedRefs = OpenLOpenAPIUtils.getUnusedSchemaRefs(openAPI, allUsedSchemaRefs.keySet());

        Map<String, List<String>> childrenSchemas = OpenAPITypeUtils.getChildrenMap(openAPI);
        Set<String> parents = childrenSchemas.keySet();

        Set<String> refsWhichAreFields = OpenLOpenAPIUtils.getRefsUsedInTypes(openAPI);

        // all the requests which were used only once per project needed to be extracted
        // if it's extends from other model it will be an inline type
        Set<String> refsToExpand = allUsedSchemaRefsInRequests.entrySet()
            .stream()
            .filter(x -> x.getValue()
                .equals(1) && (!allUsedSchemaRefs.containsKey(x.getKey()) || allUsedSchemaRefs.get(x.getKey())
                    .equals(1)) && !parents.contains(x.getKey()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

        // path + schemas
        Map<String, Set<String>> allRefsInResponses = OpenLOpenAPIUtils.getAllUsedRefResponses(openAPI, jxPathContext);

        // all the paths which have primitive responses are possible spreadsheets too
        Set<String> primitiveReturnsPaths = allRefsInResponses.entrySet()
            .stream()
            .filter(entry -> entry.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

        // searching for paths which response models are not included in ANY requestBody
        Map<String, Set<String>> pathWithPotentialSprResult = allRefsInResponses.entrySet()
            .stream()
            .filter(entry -> !entry.getValue().isEmpty() && entry.getValue()
                .stream()
                .noneMatch(allUsedSchemaRefsInRequests::containsKey))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Set<String> spreadsheetPaths = allRefsInResponses.keySet()
            .stream()
            .filter(x -> !pathWithPotentialSprResult.containsKey(x) && !primitiveReturnsPaths.contains(x))
            .collect(Collectors.toSet());

        Set<String> spreadsheetResultRefs = pathWithPotentialSprResult.values()
            .stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toSet());

        List<DatatypeModel> dts = new ArrayList<>();
        List<SpreadsheetModel> spreadsheetModels = extractSprModels(openAPI,
            jxPathContext,
            pathWithPotentialSprResult.keySet(),
            primitiveReturnsPaths,
            refsToExpand,
            spreadsheetPaths,
            dts,
            pathInfos);

        Set<String> datatypeRefs = allUsedSchemaRefs.keySet()
            .stream()
            .filter(
                x -> !(spreadsheetResultRefs.contains(x) || refsToExpand.contains(x)) || refsWhichAreFields.contains(x))
            .collect(Collectors.toSet());

        dts.addAll(extractDataTypeModels(openAPI, datatypeRefs, false));
        if (generateUnusedModels) {
            dts.addAll(extractDataTypeModels(openAPI, allUnusedRefs, true));
        }
        Map<String, List<InputParameter>> sprTypeNames = spreadsheetModels.stream()
            .collect(Collectors.toMap(SpreadsheetModel::getName, SpreadsheetModel::getParameters));
        fillStepValues(spreadsheetModels, sprTypeNames);
        boolean isRuntimeContextProvided = findRuntimeContext(sprTypeNames.values());
        return new ProjectModel(projectName, isRuntimeContextProvided, dts, spreadsheetModels, pathInfos);
    }

    private boolean findRuntimeContext(Collection<List<InputParameter>> inputParameters) {
        boolean result = true;
        for (List<InputParameter> ip : inputParameters) {
            boolean sprHasRC = containsRuntimeContext(ip);
            if (!sprHasRC) {
                result = false;
                break;
            }
        }
        return result;
    }

    private void fillStepValues(final List<SpreadsheetModel> spreadsheetModels,
            final Map<String, List<InputParameter>> sprTypeNames) {
        for (SpreadsheetModel spreadsheetModel : spreadsheetModels) {
            fillStepValues(sprTypeNames, spreadsheetModel);
        }
    }

    public boolean containsRuntimeContext(final Collection<InputParameter> inputParameters) {
        return inputParameters.stream().anyMatch(x -> x.getType().equals(DEFAULT_RUNTIME_CONTEXT));
    }

    private void fillStepValues(final Map<String, List<InputParameter>> sprTypeNames,
            final SpreadsheetModel spreadsheetModel) {
        for (StepModel step : spreadsheetModel.getSteps()) {
            String type = ARRAY_MATCHER.matcher(step.getType()).replaceAll("");
            if (sprTypeNames.containsKey(type)) {
                List<InputParameter> inputParameters = sprTypeNames.get(type);
                String value = String.join(",", Collections.nCopies(inputParameters.size(), "null"));
                step.setValue(makeCall(type, value));
            }
        }
    }

    private List<SpreadsheetModel> extractSprModels(OpenAPI openAPI,
            JXPathContext jxPathContext,
            Set<String> pathWithPotentialSprResult,
            Set<String> pathsWithPrimitiveReturns,
            Set<String> refsToExpand,
            Set<String> pathsWithSpreadsheets,
            List<DatatypeModel> dts,
            List<PathInfo> pathInfos) {
        List<SpreadsheetModel> spreadSheetModels = new ArrayList<>();
        Paths paths = openAPI.getPaths();
        if (paths != null) {
            extractSpreadsheets(openAPI,
                jxPathContext,
                pathWithPotentialSprResult,
                refsToExpand,
                dts,
                pathInfos,
                spreadSheetModels,
                paths,
                PathType.SPREADSHEET_RESULT_PATH);
            extractSpreadsheets(openAPI,
                jxPathContext,
                pathsWithPrimitiveReturns,
                refsToExpand,
                dts,
                pathInfos,
                spreadSheetModels,
                paths,
                PathType.SIMPLE_RETURN_PATH);
            extractSpreadsheets(openAPI,
                jxPathContext,
                pathsWithSpreadsheets,
                refsToExpand,
                dts,
                pathInfos,
                spreadSheetModels,
                paths,
                PathType.SPREADSHEET_PATH);
        }
        return spreadSheetModels;
    }

    private void extractSpreadsheets(OpenAPI openAPI,
            JXPathContext jxPathContext,
            Set<String> pathWithPotentialSprResult,
            Set<String> refsToExpand,
            List<DatatypeModel> dts,
            List<PathInfo> pathInfos,
            List<SpreadsheetModel> spreadSheetModels,
            Paths paths,
            PathType spreadsheetResultPath) {
        for (String path : pathWithPotentialSprResult) {
            PathItem pathItem = paths.get(path);
            if (pathItem != null) {
                SpreadsheetModel spr = extractSpreadsheetModel(openAPI,
                    jxPathContext,
                    pathItem,
                    path,
                    refsToExpand,
                    spreadsheetResultPath,
                    dts,
                    pathInfos);
                spreadSheetModels.add(spr);
            }
        }
    }

    private SpreadsheetModel extractSpreadsheetModel(OpenAPI openAPI,
            JXPathContext jxPathContext,
            PathItem pathItem,
            String path,
            Set<String> refsToExpand,
            PathType pathType,
            List<DatatypeModel> dts,
            List<PathInfo> pathInfos) {
        SpreadsheetModel spr = new SpreadsheetModel();
        PathInfo pathInfo = new PathInfo();
        pathInfo.setOriginalPath(path);
        String usedSchemaInResponse = OpenLOpenAPIUtils.getUsedSchemaInResponse(jxPathContext, pathItem);
        boolean isArray = usedSchemaInResponse.endsWith("[]");
        Schema<?> schema;
        List<InputParameter> parameters = OpenLOpenAPIUtils
            .extractParameters(jxPathContext, openAPI, refsToExpand, pathItem, dts, path);
        String normalizedPath = replaceBrackets(path);
        String formattedName = normalizeName(normalizedPath);
        spr.setName(formattedName);
        spr.setParameters(parameters);
        pathInfo.setFormattedPath(formattedName);
        pathInfo.setOperation(findOperation(pathItem));
        List<StepModel> stepModels = new ArrayList<>();
        if (PathType.SPREADSHEET_RESULT_PATH == pathType) {
            String nameOfSchema = usedSchemaInResponse;
            if (isArray) {
                nameOfSchema = ARRAY_MATCHER.matcher(usedSchemaInResponse).replaceAll("");
            }
            schema = getSchemas(openAPI).get(nameOfSchema);
            spr.setType(SPREADSHEET_RESULT);
            pathInfo.setReturnType(OBJECT);
            if (schema != null) {
                Map<String, Schema> properties = schema.getProperties();
                if (CollectionUtils.isNotEmpty(properties)) {
                    stepModels = properties.entrySet().stream().map(this::extractStep).collect(Collectors.toList());
                }
            }
        } else if (PathType.SPREADSHEET_PATH == pathType) {
            pathInfo.setReturnType(OBJECT);
            spr.setType(usedSchemaInResponse);
            stepModels = Collections
                .singletonList(new StepModel(RESULT, usedSchemaInResponse, makeValue(usedSchemaInResponse)));
        } else {
            pathInfo.setReturnType(usedSchemaInResponse);
            spr.setType(usedSchemaInResponse);
            stepModels = Collections
                .singletonList(new StepModel(formattedName, usedSchemaInResponse, makeValue(usedSchemaInResponse)));
        }
        spr.setSteps(stepModels);
        pathInfos.add(pathInfo);
        return spr;
    }

    private String findOperation(PathItem pathItem) {
        String result = "";
        if (pathItem != null) {
            Map<PathItem.HttpMethod, Operation> operationsMap = pathItem.readOperationsMap();
            if (CollectionUtils.isNotEmpty(operationsMap)) {
                if (operationsMap.get(PathItem.HttpMethod.GET) != null) {
                    result = PathItem.HttpMethod.GET.name();
                } else if (operationsMap.get(PathItem.HttpMethod.POST) != null) {
                    result = PathItem.HttpMethod.POST.name();
                } else {
                    result = operationsMap.keySet().iterator().next().name();
                }
            }
        }
        return result;
    }

    private String replaceBrackets(String path) {
        return PARAMETERS_BRACKETS_MATCHER.matcher(path).replaceAll("");
    }

    private List<DatatypeModel> extractDataTypeModels(OpenAPI openAPI,
            Set<String> allTheRefsWhichAreDatatypes,
            boolean unused) {
        List<DatatypeModel> result = new ArrayList<>();
        for (String datatypeRef : allTheRefsWhichAreDatatypes) {
            String schemaName;
            if (unused) {
                schemaName = datatypeRef;
            } else {
                schemaName = getSimpleName(datatypeRef);
            }
            Schema<?> schema = getSchemas(openAPI).get(schemaName);
            if (schema != null) {
                DatatypeModel dm = createModel(openAPI, schemaName, schema);
                result.add(dm);
            }
        }
        return result;
    }

    private DatatypeModel createModel(OpenAPI openAPI, String schemaName, Schema<?> schema) {
        DatatypeModel dm = new DatatypeModel(normalizeName(schemaName));
        Map<String, Schema> properties;
        List<FieldModel> fields = new ArrayList<>();
        if (schema instanceof ComposedSchema) {
            String parentName = OpenAPITypeUtils.getParentName((ComposedSchema) schema, openAPI);
            properties = OpenAPITypeUtils.getFieldsOfChild((ComposedSchema) schema);
            dm.setParent(parentName);
        } else {
            properties = schema.getProperties();
        }
        if (properties != null) {
            for (Map.Entry<String, Schema> property : properties.entrySet()) {
                fields.add(extractField(property));
            }
        }
        dm.setFields(fields);
        return dm;
    }

    private FieldModel extractField(Map.Entry<String, Schema> property) {
        String propertyName = property.getKey();
        Schema<?> valueSchema = property.getValue();

        String typeModel = OpenAPITypeUtils.extractType(valueSchema);
        Object defaultValue = valueSchema.getDefault();

        return new FieldModel(propertyName, typeModel, defaultValue);
    }

    private StepModel extractStep(Map.Entry<String, Schema> property) {
        String propertyName = property.getKey();
        Schema<?> valueSchema = property.getValue();
        String typeModel = OpenAPITypeUtils.extractType(valueSchema);
        String value = makeValue(typeModel);
        return new StepModel(normalizeName(propertyName), typeModel, value);
    }

    private String makeValue(String type) {
        String result = "";
        if (StringUtils.isNotBlank(type)) {
            if (OpenAPITypeUtils.isSimpleType(type)) {
                result = OpenAPITypeUtils.getSimpleValue(type);
            } else {
                result = createNewInstance(type);
            }
        }
        return result;
    }

    private String createNewInstance(String type) {
        StringBuilder result = new StringBuilder().append("=").append("new ").append(type);
        if (type.endsWith("[]")) {
            result.append("{}");
        } else {
            result.append("()");
        }
        return result.toString();
    }

    private String makeCall(String type, String value) {
        return "=" + type + "(" + value + ")";
    }
}
