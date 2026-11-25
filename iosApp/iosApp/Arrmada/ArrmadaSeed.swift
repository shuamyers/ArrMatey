import Foundation
import Shared

/// Arrmada: pre-adds the household's services on first launch from a private `ArrmadaSeed.json`
/// bundled with personal builds (git-ignored; see `iosApp/ArrmadaSeed.example.json`).
/// Only types, names and addresses are added — the user pastes each API key once.
enum ArrmadaSeed {
    private static let doneKey = "arrmada.seed.done.v1"

    static func runIfNeeded() {
        guard !UserDefaults.standard.bool(forKey: doneKey),
              let url = Bundle.main.url(forResource: "ArrmadaSeed", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else { return }

        ArrmadaSeedHelper().seed(seedJson: json) { ok, summary in
            NSLog("Arrmada seed: %@", summary)
            // Run once: a service the user deletes later is not added back.
            if ok.boolValue {
                UserDefaults.standard.set(true, forKey: doneKey)
            }
        }
    }
}
