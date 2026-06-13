import SwiftUI

struct ConversationView: View {
    @State private var model: ConversationViewModel
    @State private var draft = ""
    @State private var recipient: String
    private let mySub: String

    init(model: ConversationViewModel, recipientSub: String, mySub: String) {
        _model = State(initialValue: model)
        _recipient = State(initialValue: recipientSub)
        self.mySub = mySub
    }

    var body: some View {
        VStack {
            Text("You: \(mySub)")                               // your own sub — read it to the other device
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .padding(.horizontal)
            TextField("Recipient sub", text: $recipient)        // who to send to (the other user's sub)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(.footnote.monospaced())
                .padding(.horizontal)
            if let error = model.error {                         // transport/auth errors, never silently dropped
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.white)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(Color.red)
                    .onTapGesture { model.dismissError() }
            }
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(model.timeline) { msg in
                        HStack {
                            if msg.mine { Spacer() }
                            Text(msg.text)
                                .padding(.vertical, 9)
                                .padding(.horizontal, 14)
                                .background(msg.mine ? Color.purple : Color(.systemBackground))
                                .foregroundStyle(msg.mine ? .white : .primary)
                                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                            if !msg.mine { Spacer() }
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
            }
            .background(Color(.secondarySystemBackground))      // solid surface; bubbles overlay on top
            HStack {
                TextField("Message", text: $draft)
                    .textFieldStyle(.plain)
                    .padding(.vertical, 9)
                    .padding(.horizontal, 14)
                    .background(Color(.systemBackground), in: Capsule())
                    .overlay(Capsule().stroke(Color(.separator)))
                    .onSubmit(send)                            // press Return (hardware keyboard) to send
                Button("Send", action: send)
                    .disabled(recipient.isEmpty || draft.isEmpty)
            }
            .padding()
        }
        .task { await model.start() }
        .onDisappear { Task { await model.stop() } }
    }

    /// Sends the current draft to the recipient and clears the field. No-op if either is empty
    /// (so a stray Return on an empty field does nothing). `@State` setters are nonmutating.
    private func send() {
        guard !recipient.isEmpty, !draft.isEmpty else { return }
        let text = draft
        let target = recipient
        draft = ""
        Task { await model.send(text: text, to: target) }
    }
}
