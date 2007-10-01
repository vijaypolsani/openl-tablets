/**
 * Price editor.
 *
 * @author Andrey Naumenko
 */
var PriceEditor = Class.create();

PriceEditor.prototype = Object.extend(new BaseEditor(), {
    editor_initialize: function() {
        this.node = $(document.createElement("input"));
        this.node.setAttribute("type", "text");
        this.node.style.border = "0px none";
        this.node.style.height = (this.td.offsetHeight - (Prototype.Browser.IE ? 6 : 4)) + "px";

        this.node.style.fontFamily = this.td.style.fontFamily;
        this.node.style.fontSize = this.td.style.fontSize;
        this.node.style.fontStyle = this.td.style.fontStyle;
        this.node.style.fontWeight = this.td.style.fontWeight;
        this.node.style.textAlign = this.td.align;

        this.node.style.margin = "0px";
        this.node.style.padding = "0px";
        this.node.style.width = "100%";

        var v = this.td.innerHTML;
        if (v[0] == '$') {
            v = v.substr(1)
        }
        this.node.value = v.replace(/&nbsp;/g, " ");

        this.node.observe("click", BaseEditor.stopPropagationHandler, false);
        this.node.observe("mousedown", BaseEditor.stopPropagationHandler, false);
        this.node.observe("selectstart", BaseEditor.stopPropagationHandler, false);

        this.td.innerHTML = "";
        this.td.appendChild(this.node);
        this.node.focus();
    },

    isCancelled : function() {
        return (this.initialValue == this.getValue() || isNaN(this.getValue()));
    }
});

TableEditor.Editors["price"] = PriceEditor;
