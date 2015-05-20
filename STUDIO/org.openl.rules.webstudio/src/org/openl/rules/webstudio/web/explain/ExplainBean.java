package org.openl.rules.webstudio.web.explain;

import java.util.ArrayList;
import java.util.List;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.RequestScoped;

import org.openl.commons.web.jsf.FacesUtils;
import org.openl.meta.explanation.ExplanationNumberValue;
import org.openl.rules.ui.Explanation;
import org.openl.rules.ui.Explanator;
import org.openl.rules.webstudio.web.util.Constants;

/**
 * Request scope managed bean for Explain page.
 */
@ManagedBean
@RequestScoped
public class ExplainBean {

    private Explanation explanation;

    public ExplainBean() {
        explanation = Explanator.getRootExplanation();
    }

    public String[] getExplainTree() {
        String expandID = FacesUtils.getRequestParameter("expandID");
        String fromID = FacesUtils.getRequestParameter("from");

        if (expandID != null) {
             explanation.expand(expandID, fromID);
        }

        return explanation.htmlTable(explanation.getExplainTree());
    }

    public List<String[]> getExpandedValues() {
        List<String[]> expandedValuesList = new ArrayList<String[]>();

        List<ExplanationNumberValue<?>> expandedValues = explanation.getExpandedValues();
        for (ExplanationNumberValue<?> explanationValue : expandedValues) {
            String[] html = explanation.htmlTable(explanationValue);
            expandedValuesList.add(html);
        }

        return expandedValuesList;
    }

}
