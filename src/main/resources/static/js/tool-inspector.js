/* tool-inspector.js — Vork Tool Inspector page */

(function () {
    const searchInput = document.getElementById('toolSearch');
    const noResults = document.getElementById('noResults');

    if (!searchInput || !noResults) {
        return;
    }

    searchInput.addEventListener('input', function () {
        const q = searchInput.value.trim().toLowerCase();
        let totalVisible = 0;

        document.querySelectorAll('.category-section').forEach(function (section) {
            const rows = section.querySelectorAll('.tool-row');
            let visible = 0;
            rows.forEach(function (row) {
                const show = !q || row.textContent.toLowerCase().includes(q);
                row.classList.toggle('hidden', !show);
                if (show) visible += 1;
            });
            section.classList.toggle('hidden', q.length > 0 && visible === 0);
            totalVisible += visible;
        });

        noResults.classList.toggle('hidden', q.length === 0 || totalVisible > 0);
    });
})();
