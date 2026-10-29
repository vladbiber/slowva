// Decode one Spotify track to raw s16le stereo 44.1 kHz without a Connect device.
// Output "-" streams to stdout for playback; a path writes a whole file for reverse.
// Runs as fast as download and decoding allow; a closed pipe ends the process.
use std::{env, process::exit};

use librespot_core::{cache::Cache, config::SessionConfig, session::Session, SpotifyUri};
use librespot_playback::{
    audio_backend,
    config::{AudioFormat, Bitrate, PlayerConfig},
    mixer::NoOpVolume,
    player::{Player, PlayerEvent},
};

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let _ = log::set_logger(&StderrLog).map(|()| log::set_max_level(log::LevelFilter::Warn));
    let mut args = env::args().skip(1);
    let (Some(cache_dir), Some(uri), Some(output)) =
        (args.next(), args.next(), args.next())
    else {
        eprintln!("usage: slowva-decode <librespot-cache> <track-uri> <output|-> [bitrate] [start-ms]");
        exit(1);
    };
    let bitrate = match args.next().as_deref().unwrap_or("320") {
        "96" => Bitrate::Bitrate96,
        "160" => Bitrate::Bitrate160,
        "320" => Bitrate::Bitrate320,
        value => {
            eprintln!("invalid bitrate: {value}");
            exit(1);
        }
    };

    let start_ms: u32 = match args.next() {
        Some(v) => v.parse().unwrap_or_else(|_| {
            eprintln!("invalid start: {v}");
            exit(1);
        }),
        None => 0,
    };
    let output = (output != "-").then_some(output);

    let cache = Cache::new(Some(cache_dir.as_str()), None::<&str>, None::<&str>, None)
        .unwrap_or_else(|e| {
            eprintln!("cache: {e}");
            exit(2);
        });
    let credentials = cache.credentials().unwrap_or_else(|| {
        eprintln!("no cached librespot credentials");
        exit(3);
    });
    let track = SpotifyUri::from_uri(&uri).unwrap_or_else(|e| {
        eprintln!("track URI: {e}");
        exit(4);
    });
    if !track.is_playable() {
        eprintln!("URI is not a playable track");
        exit(4);
    }

    let session = Session::new(SessionConfig::default(), None);
    session.connect(credentials, false).await.unwrap_or_else(|e| {
        eprintln!("connect: {e}");
        exit(5);
    });

    let mut config = PlayerConfig::default();
    config.bitrate = bitrate;
    config.gapless = false;
    config.ditherer = None;
    let backend = audio_backend::find(Some("pipe".to_owned())).expect("pipe backend");
    let player = Player::new(config, session, Box::new(NoOpVolume), move || {
        backend(output.clone(), AudioFormat::S16)
    });
    let mut events = player.get_player_event_channel();
    player.load(track, true, start_ms);

    while let Some(event) = events.recv().await {
        match event {
            PlayerEvent::EndOfTrack { .. } => {
                player.stop();
                return;
            }
            PlayerEvent::Unavailable { .. } => {
                eprintln!("track unavailable");
                player.stop();
                exit(6);
            }
            _ => {}
        }
    }
    eprintln!("decoder stopped before end of track");
    exit(7);
}


/// librespot's warnings and errors go to stderr, which the app keeps in spotify.log.
struct StderrLog;

impl log::Log for StderrLog {
    fn enabled(&self, m: &log::Metadata) -> bool { m.level() <= log::Level::Warn }
    fn log(&self, r: &log::Record) {
        if self.enabled(r.metadata()) { eprintln!("{} {}: {}", r.level(), r.target(), r.args()); }
    }
    fn flush(&self) {}
}
