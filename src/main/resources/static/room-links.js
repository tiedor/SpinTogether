window.addEventListener('load', () => {
  const match = location.pathname.match(/^\/room\/([A-Z2-9]{6})$/i);
  const room = document.querySelector('#room');
  if (room) new MutationObserver(() => {
    const code = room.textContent.trim();
    if (/^[A-Z2-9]{6}$/i.test(code)) history.replaceState({}, '', '/room/' + code);
  }).observe(room, { childList: true, characterData: true, subtree: true });
  if (!match) return;
  const input = document.querySelector('#code');
  const join = document.querySelector('#join');
  if (!input || !join) return;
  input.value = match[1].toUpperCase();
  const tryJoin = () => { if (document.querySelector('#home').style.display !== 'none') join.click(); };
  setTimeout(tryJoin, 300);
  setTimeout(tryJoin, 1000);
});

let replayingSpin = false;
document.addEventListener('click', event => {
  const button = event.target.closest('#all');
  if (!button || replayingSpin) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  document.querySelectorAll('.wheel').forEach((wheel, index) => {
    const extraTurn = 6 + Math.floor(Math.random() * 2);
    wheel.style.transform = `rotate(${extraTurn * 360 + index * 47}deg)`;
  });
  setTimeout(() => {
    replayingSpin = true;
    button.click();
    replayingSpin = false;
  }, 4100);
}, true);

const wheelStyle = document.createElement('style');
wheelStyle.textContent = `
  .card { position: relative; }
  .wheel-pointer { position:absolute; z-index:10; left:50%; top:58px; margin-left:-15px;
    width:0; height:0; border-left:15px solid transparent; border-right:15px solid transparent;
    border-top:28px solid white; filter:drop-shadow(0 2px 2px #000); }
  .single-spin { display:block; margin:14px auto 6px; background:#f59e0b; color:#111827; font-weight:800; }
`;
document.head.append(wheelStyle);

const enhanceWheels = () => document.querySelectorAll('.wheel').forEach((wheel, index) => {
  const card = wheel.closest('.card');
  if (card && !card.querySelector('.wheel-pointer')) card.insertAdjacentHTML('afterbegin', '<span class="wheel-pointer"></span>');
  const labels = [...wheel.querySelectorAll('.value')];
  const sliceAngle = 360 / Math.max(labels.length, 1);
  wheel.style.background = `conic-gradient(${labels.map((_, index) =>
    `hsl(${Math.round(index * 360 / labels.length)}, 78%, 54%) ${index * sliceAngle}deg ${(index + 1) * sliceAngle}deg`
  ).join(',')})`;
  labels.forEach(label => {
    if (!label.dataset.sliceAngle) label.dataset.sliceAngle = (label.style.transform.match(/rotate\(([-\d.]+)deg\)/) || [, 0])[1];
    const angle = Number(label.dataset.sliceAngle);
    label.style.transform = `rotate(${angle + 180}deg) translate(72px)`;
  });
  if (!card || card.querySelector('.single-spin')) return;
  const button = document.createElement('button');
  button.className = 'single-spin';
  button.textContent = 'Spin this wheel';
  button.addEventListener('click', () => {
    const values = [...wheel.querySelectorAll('.value')];
    const winner = Math.floor(Math.random() * Math.max(values.length, 1));
    wheel.style.transform = `rotate(${(6 + Math.floor(Math.random() * 2)) * 360 + winner * 360 / Math.max(values.length, 1)}deg)`;
    const result = card.querySelector('p');
    if (result) result.textContent = `Result: ${values[winner]?.textContent || ''}`;
  });
  card.insertBefore(button, card.querySelector('.del'));
});
new MutationObserver(enhanceWheels).observe(document.body, { childList: true, subtree: true });
window.addEventListener('load', enhanceWheels);
