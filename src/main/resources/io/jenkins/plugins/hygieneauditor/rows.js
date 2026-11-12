(function () {
  // Makes a whole findings row select that finding. Links, buttons and form controls inside the row keep working,
  // and so do text selection and modified clicks (new tab). Without script the link in the row still works.
  function interactive(target) {
    return target.closest("a, button, input, select, textarea, label, summary");
  }
  function go(row) {
    var href = row.getAttribute("data-ha-href");
    if (href) {
      window.location.href = href;
    }
  }
  document.addEventListener("click", function (event) {
    var row = event.target.closest("tr[data-ha-href]");
    if (!row || interactive(event.target) || event.defaultPrevented) {
      return;
    }
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    var selection = window.getSelection();
    if (selection && !selection.isCollapsed) {
      return;
    }
    go(row);
  });
})();
