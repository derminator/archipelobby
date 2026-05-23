document.addEventListener("DOMContentLoaded", function () {
    var checkboxes = document.querySelectorAll('input[name="locationIds"]');
    var selectAll = document.getElementById("select-all");
    var deselectAll = document.getElementById("deselect-all");

    if (selectAll) {
        selectAll.addEventListener("click", function () {
            checkboxes.forEach(function (cb) { cb.checked = true; });
        });
    }
    if (deselectAll) {
        deselectAll.addEventListener("click", function () {
            checkboxes.forEach(function (cb) { cb.checked = false; });
        });
    }
});
