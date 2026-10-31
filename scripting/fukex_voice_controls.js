export const manifest = {
  id: 'fukex-voice-controls',
  name: 'FukeX Voice Controls',
  version: '1.0',
  author: 'Backtalk Assistant',
  minApiVersion: 1,
  testedApiVersion: 4,
  description: 'Voice commands and quick actions for the FukeX Music Player (Play, Pause, Next, Previous, Shuffle, Repeat, Lyrics, Equalizer, Queue, YouTube Search, and Track Info).',
  apps: ['com.boostofstudios.fukex'],
  permissions: ['input', 'screen', 'actions', 'speech'],
  commands: [
    {
      id: 'play_pause',
      title: 'Play / Pause',
      voice: ['play', 'pause', 'play pause', 'resume', 'stop music'],
      menu: true,
    },
    {
      id: 'next_track',
      title: 'Next Track',
      voice: ['next track', 'next song', 'skip track', 'skip song', 'next'],
      menu: true,
    },
    {
      id: 'previous_track',
      title: 'Previous Track',
      voice: ['previous track', 'previous song', 'last song', 'previous'],
      menu: true,
    },
    {
      id: 'toggle_shuffle',
      title: 'Toggle Shuffle',
      voice: ['shuffle', 'toggle shuffle', 'shuffle mode'],
      menu: true,
    },
    {
      id: 'toggle_repeat',
      title: 'Toggle Repeat',
      voice: ['repeat', 'toggle repeat', 'repeat mode'],
      menu: true,
    },
    {
      id: 'open_queue',
      title: 'Open Queue',
      voice: ['open queue', 'show queue', 'queue', 'play queue'],
      menu: true,
    },
    {
      id: 'open_lyrics',
      title: 'Open Lyrics',
      voice: ['open lyrics', 'show lyrics', 'lyrics'],
      menu: true,
    },
    {
      id: 'open_equalizer',
      title: 'Open Equalizer',
      voice: ['open equalizer', 'show equalizer', 'equalizer', 'eq'],
      menu: true,
    },
    {
      id: 'open_library',
      title: 'Open Library',
      voice: ['open library', 'show library', 'library', 'my music'],
      menu: true,
    },
    {
      id: 'open_youtube',
      title: 'YouTube Music Search',
      voice: ['youtube search', 'search music', 'online search'],
      menu: true,
    },
    {
      id: 'track_info',
      title: 'Track Info',
      voice: ['what is playing', 'current song', 'song info', 'track info', 'what song is this'],
      menu: true,
    },
  ],
};

// Helper: Click node or its clickable ancestor
async function clickNodeOrParent(node) {
  if (!node) return false;
  if (node.clickable) {
    return await node.click();
  }
  let p = node.parent;
  while (p) {
    if (p.clickable) {
      return await p.click();
    }
    p = p.parent;
  }
  return await node.click();
}

// Helper: Find element on screen matching keywords in text or contentDescription
function findElement(keywords) {
  const words = Array.isArray(keywords) ? keywords : [keywords];
  for (const word of words) {
    const w = word.toLowerCase();
    // 1. Exact contentDescription or text match
    let node = backtalk.screen.find({ contentDescription: word }) ||
               backtalk.screen.find({ text: word });
    if (node) return node;

    // 2. Contains match
    node = backtalk.screen.find({ textContains: w });
    if (node) return node;

    // 3. Search all nodes for case-insensitive match
    const all = backtalk.screen.findAll({ visible: true }, 150);
    for (const item of all) {
      const desc = (item.contentDescription || '').toLowerCase();
      const txt = (item.text || '').toLowerCase();
      const id = (item.id || '').toLowerCase();
      if (desc.includes(w) || txt.includes(w) || id.includes(w)) {
        return item;
      }
    }
  }
  return null;
}

// Helper: Find and click element, with speech announcement
async function clickAndAnnounce(keywords, successMsg, failMsg) {
  const node = findElement(keywords);
  if (node) {
    const success = await clickNodeOrParent(node);
    if (success !== false) {
      if (successMsg) backtalk.speak(successMsg);
      return true;
    }
  }
  if (failMsg) backtalk.speak(failMsg);
  return false;
}

// 1. Play / Pause
backtalk.onCommand('play_pause', async () => {
  // Check if Pause or Play button exists
  const pauseBtn = findElement(['pause', 'pause playback']);
  if (pauseBtn) {
    await clickNodeOrParent(pauseBtn);
    backtalk.speak('Paused');
    return;
  }
  const playBtn = findElement(['play', 'resume', 'start playback']);
  if (playBtn) {
    await clickNodeOrParent(playBtn);
    backtalk.speak('Playing');
    return;
  }
  backtalk.speak('Play/Pause button not found');
});

// 2. Next Track
backtalk.onCommand('next_track', async () => {
  await clickAndAnnounce(['next', 'next track', 'skip', 'forward'], 'Next track', 'Next button not found');
});

// 3. Previous Track
backtalk.onCommand('previous_track', async () => {
  await clickAndAnnounce(['previous', 'previous track', 'rewind', 'replay'], 'Previous track', 'Previous button not found');
});

// 4. Shuffle
backtalk.onCommand('toggle_shuffle', async () => {
  await clickAndAnnounce(['shuffle', 'shuffle mode', 'shuffle on', 'shuffle off'], 'Toggled shuffle', 'Shuffle button not found');
});

// 5. Repeat
backtalk.onCommand('toggle_repeat', async () => {
  await clickAndAnnounce(['repeat', 'repeat mode', 'repeat all', 'repeat one', 'repeat off'], 'Toggled repeat', 'Repeat button not found');
});

// 6. Queue
backtalk.onCommand('open_queue', async () => {
  await clickAndAnnounce(['queue', 'current queue', 'playlist', 'queue music'], 'Opening queue', 'Queue button not found');
});

// 7. Lyrics
backtalk.onCommand('open_lyrics', async () => {
  await clickAndAnnounce(['lyrics', 'show lyrics'], 'Opening lyrics', 'Lyrics button not found');
});

// 8. Equalizer
backtalk.onCommand('open_equalizer', async () => {
  await clickAndAnnounce(['equalizer', 'eq', 'sound effects'], 'Opening equalizer', 'Equalizer button not found');
});

// 9. Library
backtalk.onCommand('open_library', async () => {
  await clickAndAnnounce(['library', 'my music', 'tracks', 'songs'], 'Opening library', 'Library button not found');
});

// 10. YouTube Search
backtalk.onCommand('open_youtube', async () => {
  await clickAndAnnounce(['youtube', 'search', 'online search'], 'Opening music search', 'Search button not found');
});

// 11. Track Info (reads current title and artist)
backtalk.onCommand('track_info', async () => {
  const items = backtalk.screen.findAll({ visible: true }, 80);
  const textItems = items.filter((item) => item.text && item.text.trim().length > 0 && !item.clickable);
  if (textItems.length >= 2) {
    const title = textItems[0].text;
    const artist = textItems[1].text;
    backtalk.speak(`Now playing ${title} by ${artist}`);
    return;
  } else if (textItems.length === 1) {
    backtalk.speak(`Now playing ${textItems[0].text}`);
    return;
  }
  backtalk.speak('Could not detect track title');
});

// --- Dynamic Voice Commands ---

// Seek forward / fast forward
backtalk.onVoiceCommand(['seek forward', 'fast forward', 'forward 10 seconds'], async () => {
  await clickAndAnnounce(['forward', 'fast forward', 'seek forward'], 'Forward', 'Fast forward button not found');
});

// Seek backward / rewind
backtalk.onVoiceCommand(['seek backward', 'rewind', 'rewind 10 seconds'], async () => {
  await clickAndAnnounce(['rewind', 'backward', 'seek backward'], 'Rewind', 'Rewind button not found');
});

// Search for song dynamically: "search for [song title]"
backtalk.onVoiceCommand('search for *', async (event) => {
  const query = event.remainder;
  if (!query) {
    backtalk.speak('Please specify what to search for');
    return;
  }
  // 1. Open search if not already on search screen
  const searchBtn = findElement(['search', 'youtube', 'online search']);
  if (searchBtn) {
    await clickNodeOrParent(searchBtn);
  }
  // 2. Find editable field
  const all = backtalk.screen.findAll({ editable: true }, 10);
  const editField = all[0] || backtalk.screen.find({ className: 'EditText' });
  if (editField) {
    await editField.setText(query);
    backtalk.speak(`Searching for ${query}`);
  } else {
    backtalk.speak(`Opened search for ${query}`);
  }
});
