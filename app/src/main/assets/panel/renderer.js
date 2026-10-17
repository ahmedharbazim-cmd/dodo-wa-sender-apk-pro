const showErr = m => { const s = document.getElementById('status'); if (s) s.textContent = 'خطأ: ' + m; };
window.addEventListener('error', e => showErr(e.message));
window.addEventListener('unhandledrejection', e => showErr((e.reason && e.reason.message) || e.reason));
const $ = id => document.getElementById(id);
const esc = v => String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const MAX = 1000;

function toAscii(s) {
  return s.replace(/[\u0660-\u0669]/g, d => d.charCodeAt(0) - 0x0660).replace(/[\u06F0-\u06F9]/g, d => d.charCodeAt(0) - 0x06F0);
}
function normalize(raw, cc) {
  let n = toAscii(raw).replace(/\D/g, '');
  if (n.startsWith('00')) n = n.slice(2);
  else if (n.startsWith('0') && cc) n = cc + n.slice(1);
  else if (cc === '20' && n.length === 10 && n.startsWith('1')) n = '20' + n;
  return n;
}
function preview() {
  const q = buildQueue();
  $('cnt').textContent = q.length ? `(${q.length} رقم — أول رقم: ${q[0].phone})` : '(0 رقم صالح)';
}
function buildQueue() {
  const cc = $('cc').value.replace(/\D/g, '');
  const seen = new Set(), q = [];
  for (const line of $('numbers').value.split('\n')) {
    if (!line.trim()) continue;
    const [num, ...nm] = line.split(/[,;\t]/);
    const phone = normalize(num, cc);
    if (phone.length < 8 || seen.has(phone)) continue;
    seen.add(phone);
    const name = nm.join(' ').trim();
    q.push({ phone, text: $('message').value.replace(/\{name\}/g, name) });
  }
  return q.slice(0, MAX);
}
async function openTab() { await window.dodo.openWA(); }
function settings() {
  return {
    minDelay: Math.max(3, +$('minD').value || 15),
    maxDelay: Math.max(+$('minD').value || 15, +$('maxD').value || 30),
    batchSize: Math.max(0, +$('bs').value || 0),
    batchPause: Math.max(0, +$('bp').value || 0)
  };
}
function saveForm() {
  dodo.storage.local.set({ form: { numbers: $('numbers').value, message: $('message').value, cc: $('cc').value,
    minD: $('minD').value, maxD: $('maxD').value, bs: $('bs').value, bp: $('bp').value } });
}
['numbers','message','cc','minD','maxD','bs','bp'].forEach(i => $(i).addEventListener('input', () => {
  saveForm(); if (i === 'numbers' || i === 'cc') preview();
}));

$('start').onclick = async () => {
  const queue = buildQueue();
  if (!queue.length) return alert('مفيش أرقام صالحة');
  const { attachment } = await dodo.storage.local.get('attachment');
  if (!$('message').value.trim() && !attachment) return alert('اكتب الرسالة أو ارفع ملف');
  const state = { hasMedia: !!attachment, queue, index: 0, running: true, log: [], sent: 0, failed: 0, nextAt: 0, status: 'بدأ...', ...settings() };
  await dodo.storage.local.set({ state });
  await openTab();
};
$('resume').onclick = async () => {
  const { state } = await dodo.storage.local.get('state');
  if (!state || state.index >= state.queue.length) return;
  Object.assign(state, settings(), { running: true, status: 'استكمال...' });
  await dodo.storage.local.set({ state });
  await openTab();
};
$('stop').onclick = async () => {
  const { state } = await dodo.storage.local.get('state');
  if (state) { state.running = false; state.status = 'تم الإيقاف'; await dodo.storage.local.set({ state }); }
};
$('csv').onclick = async () => {
  const { state } = await dodo.storage.local.get('state');
  if (!state) return;
  const rows = ['phone,result,note', ...state.log.map(l => `${l.phone},${l.ok ? 'sent' : 'failed'},${l.note || ''}`)];
  if (window.dodo.saveCsv) { dodo.saveCsv('\ufeff' + rows.join('\n')); return; }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob(['\ufeff' + rows.join('\n')], { type: 'text/csv' }));
  a.download = 'wa-results.csv'; a.click();
};

function showFile(a) {
  $('fileinfo').querySelector('small').textContent = a ? `📎 ${a.name} (${(a.size / 1024).toFixed(0)} KB)` : '';
  $('rmfile').style.display = a ? 'inline-block' : 'none';
}
$('file').onchange = async e => {
  const f = e.target.files[0];
  if (!f) return;
  if (f.size > 10 * 1024 * 1024) { alert('الحد الأقصى 10MB'); e.target.value = ''; return; }
  const data = await new Promise(r => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(f); });
  await dodo.storage.local.set({ attachment: { name: f.name, type: f.type || 'application/octet-stream', size: f.size, data } });
  showFile(f);
};
$('rmfile').onclick = async () => { await dodo.storage.local.remove('attachment'); $('file').value = ''; showFile(null); };

let timer;
function render(state) {
  if (!state) return;
  const total = state.queue.length, done = state.sent + state.failed;
  $('fill').style.width = total ? (done / total * 100) + '%' : '0';
  let st = `${state.status || ''} — تم: ${state.sent} | فشل: ${state.failed} | الإجمالي: ${total}`;
  $('status').textContent = st;
  $('log').innerHTML = state.log.slice(-40).reverse()
    .map(l => `<div class="${l.ok ? 'ok' : 'bad'}">${esc(l.phone)} — ${l.ok ? 'تم' + (l.note ? ' (' + esc(l.note) + ')' : '') : 'فشل: ' + esc(l.note || '')}</div>`).join('');
  clearInterval(timer);
  if (state.running && state.nextAt > Date.now()) {
    timer = setInterval(() => {
      const s = Math.max(0, Math.ceil((state.nextAt - Date.now()) / 1000));
      $('status').textContent = st + ` — الرسالة التالية بعد ${s} ث`;
      if (!s) clearInterval(timer);
    }, 500);
  }
}
dodo.storage.onChanged.addListener((c) => { if (c.state) render(c.state.newValue); });
(async () => {
  const { form, state, attachment } = await dodo.storage.local.get(['form', 'state', 'attachment']);
  showFile(attachment);
  if (form) { $('numbers').value = form.numbers || ''; $('message').value = form.message || ''; $('cc').value = form.cc || '20';
    $('minD').value = form.minD || 15; $('maxD').value = form.maxD || 30; $('bs').value = form.bs ?? 30; $('bp').value = form.bp ?? 5; }
  preview();
  render(state);
})();
