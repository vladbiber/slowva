// slowva-token <librespot-cache-dir> <comma-separated-scopes>
// Mints a Web API access token from the cached librespot session credentials
// (same mechanism official clients use), prints "ACCESS_TOKEN EXPIRES_IN_SECS".
// First run: SLOWVA_ACCESS_TOKEN (an OAuth token) is traded for reusable cached credentials.
use std::env;
use std::process::exit;

use librespot_core::{authentication::Credentials, cache::Cache, config::SessionConfig, session::Session};

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let mut args = env::args().skip(1);
    let (Some(dir), Some(scopes)) = (args.next(), args.next()) else {
        eprintln!("usage: slowva-token <librespot-cache-dir> <scopes>");
        exit(1);
    };
    let cache = match Cache::new(Some(dir.as_str()), None::<&str>, None::<&str>, None) {
        Ok(c) => c,
        Err(e) => { eprintln!("cache: {e}"); exit(2); }
    };
    let oauth = env::var("SLOWVA_ACCESS_TOKEN").ok().filter(|t| !t.is_empty());
    let (creds, store) = match (cache.credentials(), oauth) {
        (_, Some(token)) => (Credentials::with_access_token(token), true),
        (Some(c), None) => (c, false),
        (None, None) => {
            eprintln!("no cached credentials, sign in through slowva first");
            exit(3);
        }
    };
    let session = Session::new(SessionConfig::default(), Some(cache));
    if let Err(e) = session.connect(creds, store).await {
        eprintln!("connect: {e}");
        exit(4);
    }
    // login5 is the current token channel; keymaster (Mercury) is being shut
    // down by Spotify and may answer 403, so it is only a fallback
    match session.login5().auth_token().await {
        Ok(t) => println!("{} {}", t.access_token, t.expires_in.as_secs()),
        Err(e1) => match session.token_provider().get_token(&scopes).await {
            Ok(t) => println!("{} {}", t.access_token, t.expires_in.as_secs()),
            Err(e2) => { eprintln!("login5: {e1}\nkeymaster: {e2}"); exit(5); }
        }
    }
}

