<%@ page import="org.openl.rules.webtools.*"%>
<%@ page import="javax.faces.context.FacesContext"%>
<%@ page import="org.openl.jsf.*"%>

<%@ taglib uri="http://java.sun.com/jsf/html" prefix="h"%>
<%@ taglib uri="http://java.sun.com/jsf/core" prefix="f"%>
<%@ taglib uri="http://richfaces.ajax4jsf.org/rich" prefix="rich"%>
<%@ taglib uri="https://ajax4jsf.dev.java.net/ajax" prefix="a4j"%>
<%@ taglib uri="http://java.sun.com/jstl/core" prefix="c"%>

<jsp:useBean id='studio' scope='session' class="org.openl.rules.ui.WebStudio" />

<%@include file="../checkTimeout.jsp"%>

<%
            FacesContext fc = FacesContext.getCurrentInstance();
            TableWriterBean twb = (TableWriterBean) (fc.getApplication()
                    .getVariableResolver().resolveVariable(fc,
                    "tableWriterBean"));
            int elementID = twb.getElementID();
            String name = twb.getName();
            String text = twb.getTitle();
            org.openl.syntax.ISyntaxError[] se = twb.getSe();
            String url = twb.getUrl();
            String uri = twb.getUri();
            boolean isRunnable = twb.isRunnable();
            boolean isTestable = twb.isTestable();
            String parsView = twb.getParsView();
            String view = twb.getView();
            String s_id = twb.getSid();
%>

<html>

<head>
<meta http-equiv="Content-Type" content="text/html; charset=windows-1257">
<title><%=text%></title>
<link href="../css/style1.css" rel="stylesheet" type="text/css">
<link href="../css/tableEditor.css" rel="stylesheet" type="text/css">
<script language="javascript" type="text/javascript" src="../js/spreadsheet_navigation.js"></script>
<script type="text/javascript">
initialRow = '<%=twb.getInitialRow()%>';
initialColumn = '<%=twb.getInitialColumn()%>';

function open_win(url)
{
   window.open(url,"_blank","toolbar=no, location=no, directories=no, status=no, menubar=no, scrollbars=yes, resizable=yes, copyhistory=yes, width=900, height=700, top=20, left=100")
}

function calculateFullPosition(el) {
  var x = el.offsetLeft;
  var y = el.offsetTop;
  if (null == el.offsetParent) {
    return [x,y];
  } else {
    var p = calculateFullPosition(el.offsetParent);
    return [x+p[0],y+p[1]];
  }
}

function onScreen(e) {
   // Test whether the supplied element is visible.
   var rp = e.offsetParent;
   if (rp == null)
      return false;
   var pleft = e.offsetLeft;
   var ptop = e.offsetTop;
   while (true) {
      if (!((pleft >= rp.scrollLeft) &&
            (pleft <= rp.scrollLeft + rp.clientWidth) &&
            (ptop >= rp.scrollTop) &&
            (ptop <= rp.scrollTop + rp.clientHeight)))
         return false;
      pleft += rp.offsetLeft - rp.scrollLeft ;
      ptop += rp.offsetTop - rp.scrollTop;
      rp = rp.offsetParent;
      if (rp == null)
         return true;
   }
}


function beginEditing() {
  //alert('beginEditing');
  if ((null != lastCell) && (undefined != lastCell)) {
//      var x = lastCell.offsetLeft;
//      var y = lastCell.offsetTop;
    var p = calculateFullPosition(lastCell);
//      alert(lastCell.offsetWidth);

    isEditing = true;
    document.getElementById('popup_editor_form:x').value = p[0] + 1;
    document.getElementById('popup_editor_form:y').value = p[1] + 1;
    document.getElementById('popup_editor_form:width').value = lastCell.offsetWidth;
    document.getElementById('popup_editor_form:height').value = lastCell.offsetHeight;
    document.getElementById('popup_editor_form:button').click();

//      alert(s);
//      Richfaces.showModalPanel('mp',s);
//      document.getElementById('editor_form:cell_title').value = lastCell.title;
//      document.getElementById('editor_form:begin_editing').click();
//      isEditing = true;
  }
}

function activateInplaceEditor() {
  //alert('activateInplaceEditor');
  Richfaces.showModalPanel('popup_editor','');
}

function stopEditing() {
//  if (isEditing) {
//      var b = document.getElementById(lastCell.title + 'text' + ':' + 'submit_button');
    //b.click();
//  }
//  Richfaces.hideModalPanel('popup_editor');
//  isEditing = false;
}

function stopEditing2() {
//  if (isEditing) {
//      var b = document.getElementById(lastCell.title + 'text' + ':' + 'submit_button');
    //b.click();
//  }
  Richfaces.hideModalPanel('popup_editor');
  isEditing = false;
}

function refreshSelectionAfter() {
  //document.getElementById('top_editor_form:text').value = document.getElementById(lastCell.title + 'text').innerHTML;
  var pos = extractPosition(lastCell.title);
  //alert(pos);
  document.getElementById('top_editor_form:row').value = pos[0];
  document.getElementById('top_editor_form:column').value = pos[1];
  document.getElementById('top_editor_form:elementID').value = '<%=elementID%>';
  document.getElementById('top_editor_form:cell_title').value = lastCell.title;

  document.getElementById('editor_form:row').value = pos[0];
  document.getElementById('editor_form:column').value = pos[1];
  document.getElementById('editor_form:elementID').value = '<%=elementID%>';
  document.getElementById('editor_form:cell_title').value = lastCell.title;

  document.getElementById('popup_editor_form:row').value = pos[0];
  document.getElementById('popup_editor_form:column').value = pos[1];
  document.getElementById('popup_editor_form:elementID').value = '<%=elementID%>';
  document.getElementById('popup_editor_form:cell_title').value = lastCell.title;
}
</script>

</head>

<body onkeydown='javascript:bodyOnKeyUp(event);'>
<table>
  <tr>
    <td><img src="../images/excel-workbook.png" /> <a class="left" href="showLinks.jsp?<%=url%>" target="show_app_hidden"
      title="<%=uri%>"> &nbsp;<%=text + " : " + name%></a> <%
                 if (isRunnable && se.length == 0) {
                 String tgtUrl = "../treeview.jsp?title=Trace&treejsp=tracetree.jsp&relwidth=70&mainjsp=jsp/showTraceTable.jsp&elementID="
                         + elementID + "&first=true";
 %> &nbsp;<a href="runMethod.jsp?elementID=<%=elementID%>" title="Run"><img border=0 src="../images/test.gif" /></a> &nbsp;<a
      onClick="open_win('<%=tgtUrl%>', 800, 600)" href="#" title="Trace"><img border=0 src="../images/trace.gif" /></a> &nbsp;<a
      href="benchmarkMethod.jsp?elementID=<%=elementID%>" title="Benchmark"><img border=0 src="../images/clock-icon.png" /></a> <%
             }

             if (isTestable && (se==null || se.length == 0)) {
                 String tgtUrl = "../treeview.jsp?title=Trace&treejsp=tracetree.jsp&relwidth=70&mainjsp=jsp/showTraceTable.jsp&elementID="
                         + elementID + "&first=true";
 %> &nbsp;<a href="runAllTests.jsp?elementID=<%=elementID%>" title="Test"><img border=0 src="../images/test_ok.gif" /></a> <%
 }
 %>
    </td>
    <td>&nbsp;<a class="image2" href="?<%=parsView%>&view=view.business"><img border=0 src="../images/business-view.png"
      title="Business View" /></a> &nbsp;<a class="image2" href="?<%=parsView%>&view=view.developer"><img border=0
      src="../images/developer-view.png" title="Developer (Full) View" /></a></td>
  </tr>
</table>

<%=studio.getModel().showErrors(elementID)%>


<%--
<a href="showTableEditor.jsp?elementID=<%=elementID%>">Edit Table</a>
<a href="showTableEditor2.jsf?elementID=<%=elementID%>">Edit Table</a>
&nbsp;<a href="copyTable.jsp?elementID=<%=elementID%>">Copy Table</a>
--%>

<div><f:view>

  <a4j:form id="editor_form">
    <h:inputHidden id="elementID" value="#{editorBean.elementID}" />
    <h:inputHidden id="cell_title" value="#{editorBean.cellTitle}" />
    <h:inputHidden id="row" value="#{editorBean.row}" />
    <h:inputHidden id="column" value="#{editorBean.column}" />
    <a4j:commandButton reRender="spreadsheet" id="begin_editing" style="visibility:hidden" action="#{editorBean.beginEditing}"
      value="click me" />
  </a4j:form>

  <a4j:form id="top_editor_form">
    <h:inputHidden id="elementID" value="#{topEditorBean.elementID}" />
    <h:inputHidden id="cell_title" value="#{topEditorBean.cellTitle}" />
    <h:inputHidden id="row" value="#{topEditorBean.row}" />
    <h:inputHidden id="column" value="#{topEditorBean.column}" />
    <%--
  <h:inputText id="text" value="#{topEditorBean.text}" size="50" />
  <a4j:commandButton reRender="spreadsheet" id="save_button" value="Save" action="#{topEditorBean.save}" />
  --%>
    <br />
    <br />
    <a4j:commandButton reRender="spreadsheet" id="add_row_before_button" value="Add row before" action="#{topEditorBean.addRowBefore}" />
    <a4j:commandButton reRender="spreadsheet" id="add_row_after_button" value="Add row after" action="#{topEditorBean.addRowAfter}" />
    <a4j:commandButton reRender="spreadsheet" id="remove_row_button" value="Remove row" action="#{topEditorBean.removeRow}" />
    <br />
    <a4j:commandButton reRender="spreadsheet" id="add_column_before_button" value="Add column before"
      action="#{topEditorBean.addColumnBefore}" />
    <a4j:commandButton reRender="spreadsheet" id="add_column_after_button" value="Add column after" action="#{topEditorBean.addColumnAfter}" />
    <a4j:commandButton reRender="spreadsheet" id="remove_column_button" value="Remove column" action="#{topEditorBean.removeColumn}" />
  </a4j:form>
  <br />


  <script type="text/javascript" src="<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/javascript/prototype/prototype-1.5.1.js"></script>
  <script type="text/javascript" src="<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/javascript/TableEditor.js"></script>
  <script type="text/javascript" src="<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/javascript/BaseEditor.js"></script>
  <script type="text/javascript" src="<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/javascript/TextEditor.js"></script>
  <script type="text/javascript" src="<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/javascript/DropdownEditor.js"></script>


  <div id="tableEditor"/>

  <script>
    var tableEditor = new TableEditor("tableEditor", "<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/jsp/tableeditor/load.jsf?elementID=<%=elementID%>");
    tableEditor.saveUrl = "<h:outputText value='#{facesContext.externalContext.request.contextPath}'/>/jsp/tableeditor/save.jsf";
  </script>


  <a4j:form id="popup_editor_form">
    <h:inputHidden id="elementID" value="#{popupEditorBean.elementID}" />
    <h:inputHidden id="cell_title" value="#{popupEditorBean.cellTitle}" />
    <h:inputHidden id="row" value="#{popupEditorBean.row}" />
    <h:inputHidden id="column" value="#{popupEditorBean.column}" />
    <h:inputHidden id="x" value="#{popupEditorBean.x}" />
    <h:inputHidden id="y" value="#{popupEditorBean.y}" />
    <h:inputHidden id="width" value="#{popupEditorBean.width}" />
    <h:inputHidden id="height" value="#{popupEditorBean.height}" />

    <a4j:commandButton id="button" style="visibility:hidden" oncomplete="javascript:activateInplaceEditor();" reRender="popup_editor"
      action="#{popupEditorBean.activatePopupEditor}" />

  </a4j:form>

  <h:panelGroup id="popup_editor" />

</f:view> <%--
&nbsp;<%=studio.getModel().showTable(elementID, view)%>
--%></div>

<%@include file="../showRuns.jsp"%>

</body>
</html>