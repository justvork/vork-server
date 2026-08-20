(function () {
    'use strict';

    var form = document.getElementById('input-form');
    if (!form) {
        return;
    }

    function trimRequiredTextFields() {
        var requiredFields = form.querySelectorAll('input[required], textarea[required]');
        for (var i = 0; i < requiredFields.length; i++) {
            var field = requiredFields[i];
            var value = typeof field.value === 'string' ? field.value.trim() : '';
            if (!value) {
                field.setCustomValidity('This field is required.');
            } else {
                field.setCustomValidity('');
                field.value = value;
            }
        }
    }

    function lockSubmitButtons(submitter) {
        var buttons = form.querySelectorAll('button[type="submit"]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].disabled = true;
            buttons[i].classList.add('opacity-70', 'cursor-not-allowed');
        }

        if (!submitter) {
            return;
        }

        if (!submitter.dataset.originalHtml) {
            submitter.dataset.originalHtml = submitter.innerHTML;
        }
        submitter.innerHTML = '<i class="fa-solid fa-spinner fa-spin mr-2"></i>Submitting...';
    }

    form.addEventListener('submit', function (event) {
        trimRequiredTextFields();

        if (!form.checkValidity()) {
            event.preventDefault();
            form.reportValidity();
            return;
        }

        var submitter = event.submitter || document.activeElement;
        lockSubmitButtons(submitter);
    });
})();
