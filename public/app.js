(() => {
  'use strict';

  const form = document.querySelector('#loan-form');
  const principalInput = document.querySelector('#principal');
  const annualRateInput = document.querySelector('#annual-rate');
  const yearsInput = document.querySelector('#term-years');
  const extraMonthsInput = document.querySelector('#term-months');
  const firstPaymentInput = document.querySelector('#first-payment-month');
  const calculateButton = document.querySelector('#calculate-button');
  const resetButton = document.querySelector('#reset-button');
  const formError = document.querySelector('#form-error');
  const emptyState = document.querySelector('#empty-state');
  const resultContent = document.querySelector('#result-content');
  const resultsHeading = document.querySelector('#results-heading');
  const staleBadge = document.querySelector('#stale-badge');
  const scheduleBody = document.querySelector('#schedule-body');
  const toggleScheduleButton = document.querySelector('#toggle-schedule');
  const downloadButton = document.querySelector('#download-button');

  const integerFormatter = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 0 });
  const decimalFormatter = new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 6 });
  const state = { result: null, showAllRows: false, submitting: false };

  setDefaultFirstPaymentMonth();

  form.addEventListener('submit', handleSubmit);
  form.addEventListener('input', markResultsStale);
  resetButton.addEventListener('click', resetForm);
  toggleScheduleButton.addEventListener('click', toggleSchedule);
  downloadButton.addEventListener('click', downloadCsv);

  document.querySelectorAll('[data-amount]').forEach((button) => {
    button.addEventListener('click', () => {
      principalInput.value = button.dataset.amount;
      principalInput.dispatchEvent(new Event('input', { bubbles: true }));
      principalInput.focus();
    });
  });

  async function handleSubmit(event) {
    event.preventDefault();
    if (state.submitting) return;

    clearErrors();
    const input = validateAndBuildInput();
    if (!input) return;

    setSubmitting(true);
    try {
      const response = await fetch('/api/calculate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
        body: new URLSearchParams(input).toString()
      });
      let data;
      try {
        data = await response.json();
      } catch (_error) {
        throw new Error('サーバーから正しい結果を受け取れませんでした。');
      }
      if (!response.ok) {
        throw new Error(data.error || '入力内容を確認してください。');
      }

      state.result = data;
      state.showAllRows = false;
      renderResult();
      staleBadge.hidden = true;
      resultContent.classList.remove('is-stale');
      resultsHeading.focus({ preventScroll: true });
      const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      resultsHeading.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' });
    } catch (error) {
      showFormError(error instanceof Error
        ? error.message
        : '計算できませんでした。通信状態を確認して、もう一度お試しください。');
    } finally {
      setSubmitting(false);
    }
  }

  function validateAndBuildInput() {
    const invalidFields = [];
    const principalRaw = principalInput.value.trim();
    const annualRateRaw = annualRateInput.value.trim();
    const yearsRaw = yearsInput.value.trim();
    const extraMonthsRaw = extraMonthsInput.value.trim();
    const firstPaymentMonth = firstPaymentInput.value;

    if (!principalRaw) {
      fieldError(principalInput, 'principal-error', '借入額を入力してください。');
      invalidFields.push(principalInput);
    } else if (!/^\d+$/.test(principalRaw)
        || Number(principalRaw) < 1
        || Number(principalRaw) > 1_000_000_000_000) {
      fieldError(principalInput, 'principal-error', '借入額は1円〜1兆円の整数で入力してください。');
      invalidFields.push(principalInput);
    }

    if (!annualRateRaw) {
      fieldError(annualRateInput, 'annual-rate-error', '年利を入力してください。');
      invalidFields.push(annualRateInput);
    } else if (!/^\d{1,3}(\.\d{1,6})?$/.test(annualRateRaw)
        || Number(annualRateRaw) < 0
        || Number(annualRateRaw) > 100) {
      fieldError(annualRateInput, 'annual-rate-error', '年利は0〜100%の範囲で入力してください。');
      invalidFields.push(annualRateInput);
    }

    let totalMonths = 0;
    if (!/^\d+$/.test(yearsRaw) || !/^\d+$/.test(extraMonthsRaw)) {
      durationError('返済期間は整数で入力してください。');
      invalidFields.push(yearsInput);
    } else {
      const years = Number(yearsRaw);
      const extraMonths = Number(extraMonthsRaw);
      totalMonths = years * 12 + extraMonths;
      if (years < 0 || years > 50 || extraMonths < 0 || extraMonths > 11
          || totalMonths < 1 || totalMonths > 600) {
        durationError('返済期間は合計1〜600か月の範囲で入力してください。');
        invalidFields.push(yearsInput);
      }
    }

    if (!/^\d{4}-\d{2}$/.test(firstPaymentMonth)) {
      fieldError(firstPaymentInput, 'first-payment-month-error', '初回支払月を入力してください。');
      invalidFields.push(firstPaymentInput);
    }

    if (invalidFields.length > 0) {
      showFormError('入力内容を確認してください。');
      invalidFields[0].focus();
      return null;
    }

    const method = form.querySelector('input[name="method"]:checked');
    return {
      principal: principalRaw,
      annualRate: annualRateRaw,
      months: String(totalMonths),
      method: method ? method.value : 'equal-payment',
      firstPaymentMonth
    };
  }

  function renderResult() {
    const result = state.result;
    if (!result) return;

    emptyState.hidden = true;
    resultContent.hidden = false;
    document.querySelector('#method-chip').textContent = result.methodLabel;

    const mainLabel = document.querySelector('#main-payment-label');
    const mainValue = document.querySelector('#main-payment');
    const paymentNote = document.querySelector('#payment-note');
    if (result.method === 'equal-payment') {
      mainLabel.textContent = '毎月の返済額（概算）';
      setLargeMoney(mainValue, result.regularPayment);
      paymentNote.textContent = result.lastPayment === result.regularPayment
        ? '最終回も同額です'
        : `最終回のみ ${money(result.lastPayment)} に調整`;
    } else {
      mainLabel.textContent = '初回の返済額（概算）';
      setLargeMoney(mainValue, result.firstPayment);
      paymentNote.textContent = `返済額は徐々に減り、最終回は ${money(result.lastPayment)}`;
    }

    document.querySelector('#conditions-summary').textContent =
      `${money(result.principal)} ・ 年利 ${decimalFormatter.format(result.annualRate)}% ・ ${integerFormatter.format(result.months)}回`;
    document.querySelector('#total-payment').textContent = money(result.totalPayment);
    document.querySelector('#total-interest').textContent = money(result.totalInterest);
    document.querySelector('#payment-count').textContent = `${integerFormatter.format(result.months)} 回`;

    const interestRatio = result.totalPayment === 0 ? 0 : result.totalInterest / result.totalPayment * 100;
    const principalRatio = Math.max(0, 100 - interestRatio);
    document.querySelector('#interest-ratio').textContent = `利息 ${formatRatio(interestRatio)}%`;
    document.querySelector('#principal-bar').style.width = `${principalRatio}%`;
    document.querySelector('#interest-bar').style.width = `${Math.max(0, interestRatio)}%`;
    document.querySelector('.breakdown-bar').setAttribute(
      'aria-label',
      `総支払額のうち元金${formatRatio(principalRatio)}%、利息${formatRatio(interestRatio)}%`);
    document.querySelector('#principal-legend').textContent = money(result.principal);
    document.querySelector('#interest-legend').textContent = money(result.totalInterest);

    renderSchedule();
  }

  function renderSchedule() {
    const result = state.result;
    if (!result) return;
    const rows = state.showAllRows ? result.schedule : result.schedule.slice(0, 12);
    const fragment = document.createDocumentFragment();
    rows.forEach((row) => {
      const tr = document.createElement('tr');
      const values = [
        `${row.number}回`,
        formatMonth(row.month),
        money(row.payment),
        money(row.principal),
        money(row.interest),
        money(row.balance)
      ];
      values.forEach((value, index) => {
        const cell = document.createElement(index === 0 ? 'th' : 'td');
        if (index === 0) cell.scope = 'row';
        cell.textContent = value;
        tr.appendChild(cell);
      });
      fragment.appendChild(tr);
    });
    scheduleBody.replaceChildren(fragment);

    toggleScheduleButton.hidden = result.schedule.length <= 12;
    toggleScheduleButton.textContent = state.showAllRows
      ? '最初の12回だけ表示'
      : `全${integerFormatter.format(result.schedule.length)}回の明細を表示`;
    toggleScheduleButton.setAttribute('aria-expanded', String(state.showAllRows));
  }

  function toggleSchedule() {
    state.showAllRows = !state.showAllRows;
    renderSchedule();
  }

  function downloadCsv() {
    const result = state.result;
    if (!result) return;
    const lines = [
      ['ローン返済予定表'],
      ['借入額', result.principal, '年利', `${result.annualRate}%`, '返済方式', result.methodLabel],
      ['総支払額', result.totalPayment, '利息総額', result.totalInterest, '返済回数', result.months],
      [],
      ['回', '支払月', '返済額（円）', '元金（円）', '利息（円）', '返済後残高（円）'],
      ...result.schedule.map((row) => [
        row.number, row.month, row.payment, row.principal, row.interest, row.balance
      ])
    ];
    const csv = '\uFEFF' + lines.map((line) => line.map(csvCell).join(',')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `返済予定表_${result.firstPaymentMonth}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function csvCell(value) {
    const text = String(value ?? '');
    return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
  }

  function markResultsStale() {
    if (!state.result || resultContent.hidden) return;
    staleBadge.hidden = false;
    resultContent.classList.add('is-stale');
  }

  function resetForm() {
    form.reset();
    setDefaultFirstPaymentMonth();
    clearErrors();
    state.result = null;
    state.showAllRows = false;
    resultContent.hidden = true;
    resultContent.classList.remove('is-stale');
    emptyState.hidden = false;
    staleBadge.hidden = true;
    scheduleBody.replaceChildren();
    principalInput.focus();
  }

  function setSubmitting(submitting) {
    state.submitting = submitting;
    calculateButton.disabled = submitting;
    calculateButton.querySelector('span').textContent = submitting ? '計算しています…' : '返済額を計算する';
    form.setAttribute('aria-busy', String(submitting));
  }

  function clearErrors() {
    formError.hidden = true;
    formError.textContent = '';
    document.querySelectorAll('.field-error').forEach((error) => {
      error.hidden = true;
      error.textContent = '';
    });
    [principalInput, annualRateInput, yearsInput, extraMonthsInput, firstPaymentInput]
      .forEach((input) => input.removeAttribute('aria-invalid'));
  }

  function fieldError(input, errorId, message) {
    input.setAttribute('aria-invalid', 'true');
    const error = document.querySelector(`#${errorId}`);
    error.textContent = message;
    error.hidden = false;
  }

  function durationError(message) {
    yearsInput.setAttribute('aria-invalid', 'true');
    extraMonthsInput.setAttribute('aria-invalid', 'true');
    const error = document.querySelector('#duration-error');
    error.textContent = message;
    error.hidden = false;
  }

  function showFormError(message) {
    formError.textContent = message;
    formError.hidden = false;
  }

  function setDefaultFirstPaymentMonth() {
    const now = new Date();
    const nextMonth = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    firstPaymentInput.value = `${nextMonth.getFullYear()}-${String(nextMonth.getMonth() + 1).padStart(2, '0')}`;
  }

  function setLargeMoney(element, value) {
    const unit = document.createElement('span');
    unit.textContent = '円';
    element.replaceChildren(document.createTextNode(integerFormatter.format(value) + ' '), unit);
  }

  function money(value) {
    return `${integerFormatter.format(value)} 円`;
  }

  function formatMonth(value) {
    const [year, month] = value.split('-');
    return `${year}年${Number(month)}月`;
  }

  function formatRatio(value) {
    if (value > 0 && value < 0.1) return '<0.1';
    return new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 1 }).format(value);
  }
})();
