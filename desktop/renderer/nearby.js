/*
 * The nearby dialog: sending a tree to a device in the same room, and receiving one.
 *
 * One <dialog>, borrowing the import review's box -- same head, same foot, same buttons -- because
 * receiving *ends* in the review, and the two should not look like they came from different
 * applications. Inside it, one step at a time: a device to pick, a code to compare, an offer to
 * accept, the bytes moving, and what happened. Each step asks for exactly one decision.
 *
 * Nothing here touches a socket, and nothing here decides anything about a family. The page asks
 * `shell.nearby` and hears back through its events; what arrives is handed to `hooks.importArrived`,
 * which is the import review the File menu already opens. This file only draws where a transfer has
 * got to and forwards what the person pressed.
 *
 * Every string that came from the other device -- its name, its file name -- is set with
 * `textContent`, never as markup. They are a stranger's words on their way into a list somebody is
 * about to click.
 */

import {
  describeProblem, codeGroups, mergeArrivals, arrivalAnnouncement, platformName, describeOffer,
  formatBytes,
} from './nearby-words.js';

const $ = (id) => document.getElementById(id);

/** A small element builder, so nothing from the network is ever parsed as markup. */
function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** What `nearby:send` can refuse with, before any connection exists, in the dialog's words. */
const SEND_REFUSALS = {
  GONE: 'That device isn’t nearby any more. Pick it again once it reappears.',
  ADDRESS: 'That isn’t an address on this network. It looks like 192.168.1.20:49813, and the other '
    + 'device shows it on its Receive screen.',
  BUSY: 'A transfer is already under way.',
  TOO_LARGE: 'This tree is larger than nearby sharing carries, which is 512 MB.',
  NOT_OPEN: 'The nearby window was closed. Open it again to send.',
  BAD_REQUEST: 'This tree couldn’t be prepared for sending.',
  VERSION: 'That device is on a different version of f-tree. Update both to share.',
};

/**
 * @param {object} options
 * @param {object} options.shell   `window.ftreeDesktop`
 * @param {object} options.hooks   what the rest of the page lends this dialog:
 *   treeName()        the open tree's name, for the heading
 *   prepareSending()  `{ bytes, counts, name }` from the page's own writer, or throws with a message
 *   importArrived({ name, bytes })  opens the import review; resolves `null` or the importer's reason
 *   setSetting(key, value)          the preferences' own path, so the menu follows
 * @param {(text: string) => string|null} [options.drawCode]  a QR code for a link, as SVG markup
 */
export function createNearby({ shell, hooks, drawCode = null }) {
  const dialog = $('nearby');
  const head = $('nearby-head');
  const title = $('nearby-title');
  const lead = $('nearby-lead');
  const body = $('nearby-body');
  const status = $('nearby-status');
  const actions = $('nearby-actions');

  const session = {
    mode: null,
    step: null,
    peers: [],
    deviceName: '',
    peerName: null,
    code: null,
    pairedByQr: false,
    offer: null,
    qr: null,
    // Set by this screen when the person stopped something, so the CANCELLED that comes back is
    // read as "you stopped it" and not as the other device's doing.
    stopping: false,
    // Set for the one close that hands a finished transfer to the review, which must not also
    // cancel it -- the sender is still waiting to hear whether the file could be read.
    handingOver: false,
    typing: false,
  };

  /* ---------------------------------------------------------------- the frame of every step */

  function paint({ step, heading, intro = '', content = [], buttons = [], said = '', focus = true }) {
    session.step = step;
    dialog.dataset.step = step;
    title.textContent = heading;
    lead.textContent = intro;
    lead.hidden = !intro;
    body.replaceChildren(...content);
    actions.replaceChildren(...buttons.map(button));
    status.textContent = said;
    // The heading, not the first button: a dialog focuses its first focusable child, and on the
    // code screen that would put Enter on one of two answers before anybody had read the question.
    if (focus) head.focus();
  }

  function button({ label, run, tone = 'quiet', id = null }) {
    const className = tone === 'danger' ? 'btn-danger nearby-danger' : `btn ${tone}`;
    return h('button', { type: 'button', class: className, id, onclick: run }, label);
  }

  const peerLabel = () => session.peerName || 'the other device';
  const Peer = () => session.peerName || 'The other device';

  function codePlate(code, { small = false } = {}) {
    const { groups, label } = codeGroups(code);
    return h('p', { class: `nearby-code${small ? ' small' : ''}`, role: 'img', 'aria-label': label },
      h('span', { 'aria-hidden': 'true' }, groups[0]),
      h('span', { 'aria-hidden': 'true' }, groups[1]));
  }

  function waiting(text) {
    return h('p', { class: 'nearby-waiting' }, h('i', { class: 'nearby-pulse', 'aria-hidden': 'true' }), text);
  }

  function progress(label) {
    const bar = h('div', {
      class: 'nearby-bar', id: 'nearby-bar', role: 'progressbar', 'aria-label': label,
      'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': '0',
    }, h('i', {}));
    return [bar, h('p', { class: 'nearby-amount', id: 'nearby-amount' })];
  }

  /** Moves the bar in place. Repainting the step for every 64 KB would take focus off Cancel. */
  function advance(done, total) {
    const share = total > 0 ? Math.min(1, done / total) : 0;
    const bar = $('nearby-bar');
    if (!bar) return;
    bar.setAttribute('aria-valuenow', String(Math.round(share * 100)));
    bar.firstChild.style.width = `${(share * 100).toFixed(1)}%`;
    $('nearby-amount').textContent = `${formatBytes(done)} of ${formatBytes(total)}`;
  }

  /* ---------------------------------------------------------------- opening and closing */

  async function open(mode) {
    if (dialog.open) return;
    Object.assign(session, {
      mode, peers: [], peerName: null, code: null, pairedByQr: false, offer: null, qr: null,
      stopping: false, handingOver: false, typing: false,
    });
    dialog.dataset.mode = mode;
    delete dialog.dataset.alarm;
    dialog.showModal();
    await start();
  }

  async function start() {
    const { mode } = session;
    paint({ step: 'opening', heading: headingFor(mode), content: [waiting('One moment…')] });

    const opened = await shell.nearby.open(mode);
    if (!dialog.open) return;
    if (opened.state === 'off') { showOff(); return; }
    if (opened.state === 'refused') { dialog.close(); return; }
    if (opened.state === 'failed') { showFailure(opened.problem); return; }

    session.deviceName = opened.deviceName;
    if (mode === 'receive') {
      session.qr = opened.qr;
      showListening();
    } else {
      session.peers = mergeArrivals([], opened.peers ?? []).list;
      showBrowsing();
    }
  }

  const headingFor = (mode) => (mode === 'send' ? 'Send to a nearby device' : 'Receive from a nearby device');

  function close() {
    if (dialog.open) dialog.close();
  }

  // Escape is Close, or Cancel when something is under way -- the close handler below stops it.
  dialog.addEventListener('close', () => {
    delete dialog.dataset.step;
    if (session.handingOver) { session.handingOver = false; return; }
    shell.nearby.close();
  });

  /* ---------------------------------------------------------------- off */

  /*
   * Nearby sharing is off until somebody turns it on, and this is the one place that asks them to --
   * at the moment they reached for it, with what it means in front of them. Turning it on goes
   * through the same setting as the preferences and the menu, so all three agree afterwards.
   */
  function showOff() {
    paint({
      step: 'off',
      heading: headingFor(session.mode),
      intro: 'Nearby sharing is off.',
      content: [
        h('p', { class: 'nearby-prose' },
          'Turned on, this computer can send a tree to f-tree on another device on the same Wi-Fi, or '
          + 'receive one from it — directly, with nothing uploaded anywhere.'),
        h('p', { class: 'nearby-prose' },
          'It can only be seen while this window is open, and nothing arrives unless you accept it.'),
      ],
      buttons: [
        { label: 'Cancel', run: close },
        {
          label: 'Turn on nearby sharing',
          tone: 'primary',
          run: async () => {
            await hooks.setSetting('nearbySharing', true);
            await start();
          },
        },
      ],
    });
  }

  /* ---------------------------------------------------------------- sending */

  /**
   * The devices, in the order they arrived. Rebuilt on its own when the list changes, so the typed
   * address below it -- and whatever has focus -- is left exactly where it was.
   */
  function peerList() {
    if (!session.peers.length) {
      const empty = waiting('Looking on this network. Open Receive from a nearby device on the other device.');
      empty.id = 'nearby-peer-list';
      return empty;
    }
    return h('ul', { class: 'nearby-peers', id: 'nearby-peer-list', 'aria-label': 'Devices nearby' },
      session.peers.map((peer) => h('li', {}, peerRow(peer))));
  }

  /*
   * One device. The same row either way, because it is the same list.
   *
   * A device on a version of f-tree this one cannot speak to is shown rather than left out (#191):
   * a row that is never drawn reads as a network fault, and "I can't see it" is the hardest thing
   * to work out over the phone. It is the same row, disabled and dimmed, and where the address
   * would be it says what to do about it instead -- the address is no use until both are updated.
   */
  function peerRow(peer) {
    if (peer.speakable === false) {
      return h('button', {
        type: 'button', class: 'nearby-peer', disabled: 'disabled', 'data-key': peer.key,
      },
      h('span', { class: 'nearby-peer-name', text: peer.name }),
      h('span', { class: 'nearby-peer-where' },
        h('span', { text: 'On a different version of f-tree. Update both to share.' })));
    }
    return h('button', {
      type: 'button', class: 'nearby-peer', 'data-key': peer.key,
      onclick: () => sendTo({ peerKey: peer.key }, peer.name),
    },
    h('span', { class: 'nearby-peer-name', text: peer.name }),
    h('span', { class: 'nearby-peer-where' },
      platformName(peer.platform) ? h('span', { text: platformName(peer.platform) }) : null,
      h('code', { text: peer.address })));
  }

  function showBrowsing({ said = '' } = {}) {
    paint({
      step: 'browse',
      heading: `Send ${hooks.treeName()}`,
      intro: 'Pick the device to send it to. Only devices on this Wi-Fi with Receive open appear here.',
      content: [peerList(), typedAddress()],
      buttons: [{ label: 'Cancel', run: close }],
      said,
    });
  }

  function repaintPeers(arrived) {
    const before = $('nearby-peer-list');
    if (!before) return;
    const focusedKey = before.contains(document.activeElement) ? document.activeElement.dataset.key : null;
    const after = peerList();
    before.replaceWith(after);
    if (focusedKey) after.querySelector(`[data-key="${CSS.escape(focusedKey)}"]`)?.focus();
    // One polite line, however many arrived at once.
    const announced = arrivalAnnouncement(arrived, session.peers.length);
    if (announced) status.textContent = announced;
  }

  /*
   * The typed address, for the networks discovery cannot cross -- a router that drops multicast,
   * or two machines on different Wi-Fi bands that still route to each other. Folded away rather
   * than shown, because the list is the ordinary way and an empty field beside it reads as a step.
   */
  function typedAddress() {
    const field = h('input', {
      type: 'text', id: 'nearby-address', class: 'nearby-input', autocomplete: 'off', spellcheck: 'false',
      placeholder: '192.168.1.20:49813', 'aria-describedby': 'nearby-address-note',
    });
    const note = h('p', { class: 'nearby-note', id: 'nearby-address-note' },
      'Shown on the other device’s Receive screen.');
    const form = h('form', {
      class: 'nearby-typed-form',
      hidden: !session.typing,
      onsubmit: (event) => {
        event.preventDefault();
        sendTo({ address: field.value.trim() }, null);
      },
    },
    h('label', { for: 'nearby-address', class: 'nearby-label' }, 'Address'),
    h('div', { class: 'nearby-typed-row' }, field, h('button', { type: 'submit', class: 'btn quiet' }, 'Connect')),
    note);

    const toggle = h('button', {
      type: 'button',
      class: 'nearby-disclose',
      'aria-expanded': String(session.typing),
      onclick: () => {
        session.typing = !session.typing;
        form.hidden = !session.typing;
        toggle.setAttribute('aria-expanded', String(session.typing));
        if (session.typing) field.focus();
      },
    }, 'Type an address instead');
    return h('div', { class: 'nearby-typed' }, toggle, form);
  }

  async function sendTo(target, name) {
    let made;
    try {
      made = await hooks.prepareSending();
    } catch (error) {
      showBrowsing({ said: error.message || SEND_REFUSALS.BAD_REQUEST });
      return;
    }
    session.peerName = name;
    session.stopping = false;
    paint({
      step: 'connecting',
      heading: name ? `Connecting to ${name}` : `Connecting to ${target.address}`,
      content: [waiting('Waiting for it to answer.')],
      buttons: [{ label: 'Cancel', run: stop }],
    });
    const result = await shell.nearby.send({ ...made, ...target });
    if (!result?.ok && dialog.open) {
      showBrowsing({ said: SEND_REFUSALS[result?.reason] ?? describeProblem('NETWORK', { role: 'send' }).body });
    }
  }

  function showCompare() {
    paint({
      step: 'compare',
      heading: 'Do the codes match?',
      intro: `${Peer()} should be showing these six digits now.`,
      content: [
        codePlate(session.code),
        h('p', { class: 'nearby-prose' },
          'Check every digit. The same code on both screens means nobody else is on this connection. '
          + 'A different one means somebody may be — so say so, and nothing will be sent.'),
      ],
      buttons: [
        { label: 'No, they’re different', tone: 'danger', run: () => shell.nearby.confirmCode(false) },
        {
          label: 'Yes, they match',
          tone: 'primary',
          run: () => {
            shell.nearby.confirmCode(true);
            paint({
              step: 'waiting',
              heading: `Waiting for ${peerLabel()}`,
              intro: 'They’re deciding whether to accept the tree.',
              content: [codePlate(session.code, { small: true }), waiting('Nothing has been sent yet.')],
              buttons: [{ label: 'Cancel', run: stop }],
            });
          },
        },
      ],
    });
  }

  function showSending(done, total) {
    if (session.step !== 'sending') {
      paint({
        step: 'sending',
        heading: `Sending to ${peerLabel()}`,
        content: progress('Sent so far'),
        buttons: [{ label: 'Cancel', run: stop }],
      });
    }
    advance(done, total);
    // The last byte leaving is not the end: the other side reads the file before it answers.
    if (total > 0 && done >= total) status.textContent = `Waiting for ${peerLabel()} to open it…`;
  }

  function showSent() {
    paint({
      step: 'sent',
      heading: `Sent to ${peerLabel()}`,
      intro: 'They’re looking through it now. Nothing is added to their tree until they confirm.',
      buttons: [{ label: 'Done', tone: 'primary', run: close }],
    });
  }

  /* ---------------------------------------------------------------- receiving */

  function showListening({ said = '', focus = true } = {}) {
    const where = session.qr ? `${session.qr.address}:${session.qr.port}` : null;
    const picture = session.qr && drawCode ? drawCode(session.qr.text) : null;

    const steps = h('ol', { class: 'nearby-steps' },
      h('li', {}, 'On the other device, open ', h('b', {}, 'Send to a nearby device'), '.'),
      h('li', {}, 'Pick ', h('b', { text: session.deviceName }), ' in its list, and check the two codes match.'));
    const others = [
      picture ? h('p', { class: 'nearby-or' }, 'Or scan this code with f-tree on a phone.') : null,
      where ? h('p', { class: 'nearby-or' }, 'Or type this address on the other device: ',
        h('code', { class: 'nearby-where', text: where })) : null,
    ];

    const qr = picture ? h('div', { class: 'nearby-qr' }) : null;
    if (qr) qr.innerHTML = picture; // markup from qr-picture.js, built only from the link's modules

    paint({
      step: 'listen',
      heading: 'Receive from a nearby device',
      intro: `This computer is visible as ${session.deviceName} until you close this window.`,
      content: [h('div', { class: `nearby-listen${qr ? ' with-code' : ''}` }, qr,
        h('div', { class: 'nearby-listen-words' }, steps, others))],
      buttons: [{ label: 'Close', tone: 'primary', run: close }],
      said: said || 'Waiting for a device…',
      focus,
    });
  }

  async function refreshCode() {
    if (session.mode !== 'receive' || !dialog.open) return;
    session.qr = await shell.nearby.qr();
    if (session.step === 'listen') showListening({ said: status.textContent, focus: false });
  }

  function showIncomingCode() {
    const scanned = session.pairedByQr;
    paint({
      step: 'incoming-code',
      heading: `${Peer()} is connecting`,
      intro: scanned
        ? `${Peer()} scanned the code on this screen, so there are no digits to compare.`
        : 'Check its screen shows these same six digits. It confirms first, then says what it’s sending.',
      content: scanned ? [waiting('Waiting to hear what it’s sending.')] : [codePlate(session.code)],
      buttons: [{ label: 'Stop', run: stop }],
    });
  }

  function showOffer() {
    const offer = describeOffer(session.offer, session.peerName);
    const facts = h('dl', { class: 'nearby-facts' },
      h('dt', {}, 'File'), h('dd', { text: offer.name }),
      h('dt', {}, 'Size'), h('dd', { text: offer.size }));
    paint({
      step: 'offer',
      heading: `${Peer()} wants to send you a tree`,
      intro: session.pairedByQr ? '' : `Only accept if ${peerLabel()} shows the code below.`,
      content: [
        session.pairedByQr ? null : codePlate(session.code, { small: true }),
        h('p', { class: 'nearby-claim', text: offer.claim }),
        facts,
        h('p', { class: 'nearby-prose' },
          'Accepting doesn’t change your tree. You’ll see everyone in it, and who matches whom, before '
          + 'anything is added.'),
      ],
      buttons: [
        { label: 'Decline', run: () => { session.stopping = true; shell.nearby.decline(); } },
        { label: 'Accept', tone: 'primary', run: () => { shell.nearby.accept(); showReceiving(0, session.offer.totalBytes); } },
      ],
    });
  }

  function showReceiving(done, total) {
    if (session.step !== 'receiving') {
      paint({
        step: 'receiving',
        heading: `Receiving from ${peerLabel()}`,
        content: progress('Received so far'),
        buttons: [{ label: 'Cancel', run: stop }],
      });
    }
    advance(done, total);
  }

  /*
   * The end of a transfer and the beginning of an import, and the whole point of the design: the
   * dialog closes and the import review opens, exactly as if the file had been picked from a
   * folder. The sender is told whether it could be read only once the review has it -- and the
   * visibility is given back only after that, or the answer would never reach it.
   */
  async function handOver({ name, bytes }) {
    session.handingOver = true;
    dialog.close();
    let problem = null;
    try {
      problem = await hooks.importArrived({ name, bytes });
    } catch {
      problem = 'unreadable';
    }
    await shell.nearby.importFinished(problem);
    await shell.nearby.close();
  }

  /* ---------------------------------------------------------------- stopping, and failing */

  function stop() {
    session.stopping = true;
    shell.nearby.cancel();
  }

  function showFailure(name, { importProblem = null } = {}) {
    const said = describeProblem(name, { role: session.mode, peer: session.peerName, importProblem });
    const again = session.mode === 'send'
      ? { label: 'Pick a device again', tone: 'primary', run: () => showBrowsing() }
      : { label: 'Keep waiting', tone: 'primary', run: () => showListening() };
    paint({
      step: 'failed',
      heading: said.title,
      content: [h('div', { class: `nearby-problem${said.alarm ? ' alarm' : ''}`, role: said.alarm ? 'alert' : null },
        h('p', { text: said.body }))],
      // No second try from an alarm. A mismatch means somebody may be in the middle, and the one
      // thing this screen must not do is make trying again the easy button.
      buttons: said.alarm
        ? [{ label: 'Close', tone: 'primary', run: close }]
        : [{ label: 'Close', run: close }, again],
    });
    dialog.dataset.alarm = String(said.alarm);
  }

  /** A transfer ended. Who ended it decides what this screen says. */
  function finished({ problem, importProblem }) {
    delete dialog.dataset.alarm;
    const stoppedHere = session.stopping;
    session.stopping = false;

    if (session.mode === 'send') {
      if (!problem) { showSent(); return; }
      // Somebody pressed Cancel here. They know; a failure screen would be telling them off.
      if (stoppedHere && problem === 'CANCELLED') {
        showBrowsing({ said: 'Stopped. Nothing was sent.' });
        return;
      }
      showFailure(problem, { importProblem });
      return;
    }

    // Receiving: back to listening wherever that is still honest.
    if (stoppedHere && (problem === 'CANCELLED' || problem === 'DECLINED')) {
      showListening({ said: problem === 'DECLINED' ? 'Declined. Nothing was received.' : 'Stopped. Nothing was received.' });
      return;
    }
    // A device that never got as far as a code -- an old QR, say -- is news, not a failure of this
    // screen. Said in the status line, with the screen still waiting for the device that matters.
    if (session.step === 'listen' && !describeProblem(problem, { role: 'receive' }).alarm) {
      showListening({ said: describeProblem(problem, { role: 'receive' }).body, focus: false });
      return;
    }
    showFailure(problem, { importProblem });
  }

  /* ---------------------------------------------------------------- what the shell says */

  shell.nearby.onEvent((event) => {
    if (!dialog.open && event.type !== 'receive-complete') return;
    switch (event.type) {
      case 'peers': {
        const merged = mergeArrivals(session.peers, event.peers ?? []);
        session.peers = merged.list;
        if (session.step === 'browse') repaintPeers(merged.arrived);
        break;
      }
      case 'send-code':
        session.code = event.code;
        session.peerName = event.peerName || session.peerName;
        showCompare();
        break;
      case 'send-progress':
        showSending(event.done, event.total);
        break;
      case 'send-finished':
        finished(event);
        break;
      case 'receive-code':
        session.code = event.code;
        session.pairedByQr = event.pairedByQr;
        session.peerName = event.peerName;
        showIncomingCode();
        break;
      case 'receive-offer':
        session.offer = event.offer;
        session.peerName = event.offer.peerName || session.peerName;
        showOffer();
        break;
      case 'receive-progress':
        showReceiving(event.done, event.total);
        break;
      case 'receive-complete':
        if (dialog.open) handOver(event);
        break;
      case 'receive-finished':
        finished(event);
        refreshCode();
        break;
      case 'qr-changed':
        refreshCode();
        break;
      case 'problem':
        if (event.problem === 'NETWORK' && session.step === 'opening') showFailure('NETWORK');
        else status.textContent = describeProblem(event.problem, { role: session.mode }).body;
        break;
      case 'off':
        if (dialog.open) showOff();
        break;
      default:
        break;
    }
  });

  return { open, close, get isOpen() { return dialog.open; }, get step() { return session.step; } };
}
