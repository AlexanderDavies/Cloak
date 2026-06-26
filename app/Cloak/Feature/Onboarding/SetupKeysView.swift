import SwiftUI

/// First-run onboarding: generates + publishes this device's key bundle, then advances. Pure view —
/// all behaviour lives in ``SetupKeysViewModel`` (verified by units); excluded from coverage.
struct SetupKeysView: View {
    @State var model: SetupKeysViewModel
    let onReady: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image("CloakLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 18))
            switch model.phase {
            case .working:
                ProgressView()
                    .tint(.purple)
                Text("Setting up secure keys…")
                    .foregroundStyle(.secondary)
            case .failed:
                Text("Couldn't finish setup.")
                    .foregroundStyle(.red)
                Button("Retry") { Task { await model.run() } }
                    .buttonStyle(.borderedProminent)
                    .tint(.purple)
            case .ready:
                Color.clear.onAppear(perform: onReady)
            }
        }
        .task { await model.run() }
    }
}
