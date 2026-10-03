// If the page is opened by double-click (file://) or via Live Server, talk to the Java server directly.
const SERVER = location.port === '8080' ? '' : 'http://localhost:8080';
const API = `${SERVER}/api/items`;
const icons = { Electronics: '⌁', 'ID/Keys': '⌘', Books: '▤', Other: '◇' };
const grid = document.querySelector('#itemsGrid');
const search = document.querySelector('#searchInput');
const filter = document.querySelector('#statusFilter');
const form = document.querySelector('#itemForm');
let items = [];

async function loadItems() {
  try {
    const response = await fetch(API);
    if (!response.ok) throw new Error('Could not load items');
    items = await response.json();
    render();
  } catch (error) {
    grid.innerHTML = '<div class="empty">The noticeboard is offline. Start CampusTrackServer.java on port 8080 and refresh.</div>';
    document.querySelector('#itemCount').textContent = 'Offline';
  }
}

function render() {
  const query = search.value.trim().toLowerCase();
  const selected = filter.value;
  const visible = items.filter(item => (selected === 'ALL' || item.status === selected) && (!query || `${item.name} ${item.category} ${item.location}`.toLowerCase().includes(query)));
  document.querySelector('#itemCount').textContent = `${visible.length} ${visible.length === 1 ? 'item' : 'items'}`;
  grid.innerHTML = visible.length ? visible.map(card).join('') : '<div class="empty">No items match your search yet.</div>';
}

function card(item) {
  const date = item.timestamp ? new Date(item.timestamp).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : 'Recently';
  return `<article class="item-card"><div class="card-top"><div><span class="category-icon">${icons[item.category] || icons.Other}</span><h3>${escapeHtml(item.name)}</h3><span class="category">${escapeHtml(item.category)}</span></div><span class="status status-${item.status}">${item.status}</span></div><div class="details"><span>⌖ <b>${escapeHtml(item.location)}</b></span><span>◎ <b>${escapeHtml(item.contact)}</b></span><span>Posted ${date}</span></div>${item.status !== 'CLAIMED' ? `<button class="claim" data-id="${item.id}">Mark as claimed →</button>` : ''}</article>`;
}

function escapeHtml(value) { return String(value).replace(/[&<>'"]/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#039;', '"':'&quot;' }[char])); }

form.addEventListener('submit', async event => {
  event.preventDefault();
  const message = document.querySelector('#formMessage');
  const data = Object.fromEntries(new FormData(form));
  try {
    const response = await fetch(API, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
    if (!response.ok) throw new Error('Could not post item');
    const result = await response.json();
    if (result.id) ownPostIds.add(result.id);
    form.reset();
    message.textContent = 'Posted to the noticeboard.';
    await loadItems();
  } catch (error) { message.textContent = 'Could not post. Is the Java server running?'; message.className = 'form-message error'; }
});

grid.addEventListener('click', async event => {
  const button = event.target.closest('.claim');
  if (!button) return;
  button.disabled = true;
  try { await fetch(`${API}/claim`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: Number(button.dataset.id) }) }); await loadItems(); }
  catch (error) { button.disabled = false; }
});
search.addEventListener('input', render);
filter.addEventListener('change', render);

/* ---------- Notifications ----------
   Every POLL_MS this browser asks the server for notices with id > lastSeenId.
   Each new notice shows an in-page toast, goes into the bell list, and fires a
   desktop notification if the user allowed it. Posts made from this tab are skipped
   because the form already confirms them. */
const POLL_MS = 5000;
const bellButton = document.querySelector('#bellButton');
const bellCount = document.querySelector('#bellCount');
const notifyPanel = document.querySelector('#notifyPanel');
const notifyList = document.querySelector('#notifyList');
const enableAlerts = document.querySelector('#enableAlerts');
const toastStack = document.querySelector('#toastStack');
const ownPostIds = new Set();
let lastSeenId = null;
let unread = 0;

async function checkNotifications() {
  try {
    const response = await fetch(`${SERVER}/api/notifications?since=${lastSeenId ?? 0}`);
    if (!response.ok) return;
    const data = await response.json();

    // First check only records where we are; nothing already on the board is "new".
    if (lastSeenId === null) { lastSeenId = data.latestId; return; }
    lastSeenId = Math.max(lastSeenId, data.latestId);

    const fresh = data.items.filter(item => !ownPostIds.has(item.id));
    if (!data.items.length) return;
    fresh.forEach(notify);
    await loadItems();
  } catch (error) {
    // Server offline; loadItems() already shows the offline message.
  }
}

function notify(item) {
  const text = `${item.status === 'FOUND' ? 'Found' : 'Lost'}: ${item.name} at ${item.location}`;
  showToast(item, text);
  addToBell(text);
  if ('Notification' in window && Notification.permission === 'granted') {
    new Notification('CampusTrack: new notice', { body: text, tag: `campustrack-${item.id}` });
  }
}

function showToast(item, text) {
  const toast = document.createElement('div');
  toast.className = `toast toast-${item.status}`;
  toast.setAttribute('role', 'status');
  toast.innerHTML = `<strong>New notice posted</strong><span>${escapeHtml(text)}</span><button type="button" aria-label="Dismiss">&times;</button>`;
  toast.querySelector('button').addEventListener('click', () => toast.remove());
  toastStack.appendChild(toast);
  setTimeout(() => toast.remove(), 6000);
}

function addToBell(text) {
  notifyList.querySelector('.notify-empty')?.remove();
  const li = document.createElement('li');
  li.innerHTML = `<span>${escapeHtml(text)}</span><time>${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time>`;
  notifyList.prepend(li);
  unread += 1;
  bellCount.textContent = unread > 9 ? '9+' : unread;
  bellCount.hidden = false;
}

bellButton.addEventListener('click', () => {
  const open = notifyPanel.hidden;
  notifyPanel.hidden = !open;
  bellButton.setAttribute('aria-expanded', String(open));
  if (open) { unread = 0; bellCount.hidden = true; }
});

document.addEventListener('click', event => {
  if (!notifyPanel.hidden && !event.target.closest('.notify-wrap')) {
    notifyPanel.hidden = true;
    bellButton.setAttribute('aria-expanded', 'false');
  }
});

function updateAlertButton() {
  if (!('Notification' in window)) { enableAlerts.textContent = 'Desktop alerts unsupported'; enableAlerts.disabled = true; return; }
  if (Notification.permission === 'granted') { enableAlerts.textContent = 'Desktop alerts on'; enableAlerts.disabled = true; }
  else if (Notification.permission === 'denied') { enableAlerts.textContent = 'Alerts blocked in browser'; enableAlerts.disabled = true; }
}

// Browsers only allow the permission prompt from a user click.
enableAlerts.addEventListener('click', async () => {
  await Notification.requestPermission();
  updateAlertButton();
});

updateAlertButton();
loadItems();
checkNotifications();
setInterval(checkNotifications, POLL_MS);
