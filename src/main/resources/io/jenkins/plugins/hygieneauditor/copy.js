(function () {
  document.addEventListener("click", function (event) {
    var button = event.target.closest("button[data-ha-copy]");
    if (!button) {
      return;
    }
    var source = document.getElementById(button.getAttribute("data-ha-copy"));
    if (!source) {
      return;
    }
    var original = button.textContent;
    var done = function (ok) {
      button.textContent = ok ? "Copied" : "Press Ctrl+C to copy";
      if (!ok) {
        var range = document.createRange();
        range.selectNodeContents(source);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
      }
      window.setTimeout(function () { button.textContent = original; }, 2000);
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(source.textContent).then(function () { done(true); }, function () { done(false); });
    } else {
      done(false);
    }
  });
})();
