(ns landtransport.landtransportadvisor
  "Land Transport Support Advisor client -- the *contained intelligence
  node* for the land-transport-support operations actor.

  It normalizes safety-scope intake, drafts a per-jurisdiction safety-
  scope evidence checklist, drafts the dispatch-authorization action
  (toll-lane / recovery-job / terminal-slot), and drafts the
  reconciliation-publish action. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record or a real dispatch authorization/
  reconciliation-record publication. Every output is censored
  downstream by `landtransport.governor` before anything touches the
  SSoT, and `:dispatch/authorize`/`:reconciliation/publish` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :dispatch/authorize | :reconciliation/publish | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [landtransport.facts :as facts]
            [landtransport.registry :as registry]
            [landtransport.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the record id, dispatch kind or jurisdiction. High
  confidence, low stakes."
  [_st {:keys [patch]}]
  {:summary    (str "派遣記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :dispatch/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-safety-scope
  "Per-jurisdiction safety-scope evidence checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in
  `landtransport.facts` -- the Land Transport Support Governor must
  reject this (never invent a jurisdiction's requirements)."
  [st {:keys [subject no-spec?]}]
  (let [w (store/land-dispatch st subject)
        iso3 (if no-spec? "ATL" (:jurisdiction w))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "landtransport.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :safety-scope-assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け安全スコープ必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :safety-scope-assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-dispatch-authorize
  "Draft the actual DISPATCH-AUTHORIZATION action -- authorizing a
  toll-lane dispatch, recovery-job dispatch, or terminal-slot operation
  dispatch. ALWAYS `:stake :dispatch/authorize` -- this is a REAL-WORLD
  act (a robot or an operator physically opens the toll lane, dispatches
  the recovery vehicle, or clears the terminal-slot operation), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`landtransport.phase`); the
  governor also always escalates on `:dispatch/authorize`. Two
  independent layers agree, deliberately."
  [st {:keys [subject]}]
  (let [w (store/land-dispatch st subject)
        scope-ok? (and w (true? (:safety-scope-verified? w)))
        precondition-ok? (and w
                              (case (:dispatch-kind w)
                                :recovery-job (true? (:vehicle-condition-checked? w))
                                :terminal-slot (true? (:terminal-slot-evidence-verified? w))
                                :toll-lane true
                                false))
        capacity-ok? (and w (or (not= :recovery-job (:dispatch-kind w))
                                (not (registry/recovery-capacity-exceeded?
                                      (:tow-vehicle-max-capacity-kg w)
                                      (:recovered-vehicle-weight-kg w)))))]
    {:summary    (str subject " 向け派遣認可提案"
                      (when w (str " (kind=" (name (:dispatch-kind w)) ")")))
     :rationale  (if w
                   (str "safety-scope-verified?=" scope-ok?
                        " precondition-ok?=" precondition-ok?
                        " capacity-ok?=" capacity-ok?)
                   "land-dispatchが見つかりません")
     :cites      (if w [subject] [])
     :effect     :dispatch/mark-authorized
     :value      {:land-dispatch-id subject}
     :stake      :dispatch/authorize
     :confidence (if (and scope-ok? precondition-ok? capacity-ok?)
                   0.9 0.3)}))

(defn- propose-reconciliation-publish
  "Draft the actual RECONCILIATION-PUBLISH action -- publishing the
  multi-carrier terminal-slot booking / dispatch reconciliation record
  for a previously-authorized dispatch. ALWAYS `:stake
  :reconciliation/publish` -- this is a REAL-WORLD act (real carrier/
  motorist billing and disclosure records are published), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`landtransport.phase`); the
  governor also always escalates on `:reconciliation/publish`. Two
  independent layers agree, deliberately."
  [st {:keys [subject]}]
  (let [w (store/land-dispatch st subject)
        dispatched? (and w (:dispatched? w))
        scope-ok? (and w (true? (:safety-scope-verified? w)))]
    {:summary    (str subject " 向け精算記録公開提案"
                      (when w (str " (carriers=" (:carrier-count w) ")")))
     :rationale  (if w
                   (str "dispatched?=" dispatched?
                        " safety-scope-verified?=" scope-ok?)
                   "land-dispatchが見つかりません")
     :cites      (if w [subject] [])
     :effect     :dispatch/mark-reconciled
     :value      {:land-dispatch-id subject}
     :stake      :reconciliation/publish
     :confidence (if (and dispatched? scope-ok?) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :safety-scope/intake     (normalize-intake st request)
    :safety-scope/verify     (verify-safety-scope st request)
    :dispatch/authorize      (propose-dispatch-authorize st request)
    :reconciliation/publish  (propose-reconciliation-publish st request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地上交通支援事業者(有料道路/バスターミナル/車両回収)の"
       "派遣認可・精算記録公開エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:dispatch/upsert|:safety-scope-assessment/set|:dispatch/mark-authorized|"
       ":dispatch/mark-reconciled) "
       ":stake(:dispatch/authorize か :reconciliation/publish か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の安全スコープ要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "車両状態確認・牽引容量・ターミナル枠証跡の状態を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:land-dispatch (store/land-dispatch st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Land Transport Support
  Governor escalates/holds -- an LLM hiccup can never auto-commit a
  dispatch authorization or auto-publish a reconciliation record."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :landtransportadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
