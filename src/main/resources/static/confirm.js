document.addEventListener('submit', function (e) {
    const form = e.target;
    const message = form.dataset.confirm;
    if (message && !confirm(message)) {
        e.preventDefault();
    }
});
