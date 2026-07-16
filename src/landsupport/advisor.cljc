(ns landsupport.advisor
  "Land Transport Support Advisor client -- the *contained intelligence
  node* for the land-transport-infrastructure-support operations actor
  (ISIC 5221).

  It drafts a facility-usage/transaction log entry, a maintenance-
  scheduling proposal, a structural-safety-concern flag (for human
  review -- this actor NEVER resolves one itself), and a maintenance-
  materials procurement proposal. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a real toll-lane dispatch,
  facility-equipment action, or structural-safety-clearance decision --
  see README `Scope`. Every output is censored downstream by
  `landsupport.governor` before anything touches the SSoT, and
  `:flag-structural-safety-concern` proposals NEVER auto-commit at any
  phase.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- this actor never actuates
     :op         kw             ; echoes the request op, for the store's dispatch
     :value      {..}           ; op-specific payload
     :stake      kw|nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [landsupport.store :as store]
            [langchain.model :as model]))

(defn- log-facility-record
  "Facility-usage/transaction log entry draft (toll-transaction/
  parking-occupancy/facility-usage data). The LLM only normalizes/
  validates the entry; it does not invent the facility or its
  registration status. High confidence, low stakes."
  [_st {:keys [subject entry]}]
  {:summary    (str subject " 向け利用記録エントリを提案: " (pr-str (keys entry)))
   :rationale  "入力エントリの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys entry))
   :effect     :propose
   :op         :log-facility-record
   :value      {:kind :facility-usage-log :entry entry}
   :stake      nil
   :confidence 0.95})

(defn- schedule-facility-maintenance
  "Road/bridge/facility maintenance-scheduling proposal draft -- a
  PROPOSED window only, never a dispatch of an actual maintenance
  crew or equipment action."
  [st {:keys [subject window]}]
  (let [f (store/facility st subject)]
    {:summary    (str subject " 向け保守スケジュール提案"
                      (when f (str " (facility=" (:facility-name f) ")")))
     :rationale  (if f
                   (str "施設種別=" (name (:facility-type f)) " の定期保守ウィンドウ提案。")
                   "施設記録が見つかりません。")
     :cites      (if f [subject] [])
     :effect     :propose
     :op         :schedule-facility-maintenance
     :value      {:kind :maintenance-schedule-proposal :window window}
     :stake      nil
     :confidence (if f 0.85 0.2)}))

(defn- flag-structural-safety-concern
  "Surfaces a structural-defect/traffic-hazard concern for HUMAN
  review. This actor has NO structural-safety authority: it never
  resolves, dismisses, or finalizes a clearance on a concern -- it
  only reports one exists and always escalates. The rationale
  deliberately never uses a finalization/certification phrase (see
  `landsupport.governor/finalization-phrases` docstring and
  `test/landsupport/scope_exclusion_test.clj`)."
  [st {:keys [subject observation]}]
  (let [f (store/facility st subject)]
    {:summary    (str subject (if f (str " (" (:facility-name f) ")") "")
                      ": 構造的懸念の報告を検出、人間レビューへ")
     :rationale  (str "現地観測記録"
                      (when observation (str "「" observation "」"))
                      "に基づき構造的懸念の可能性を検出。"
                      "この懸念の解消判断はこのアクターの権限外であり、"
                      "常に人間の構造技術者によるレビューへエスカレーションする。")
     :cites      (if observation [:field-observation] [])
     :effect     :propose
     :op         :flag-structural-safety-concern
     :value      {:kind :structural-safety-concern-flag :observation observation}
     :stake      :concern/flagged
     :confidence 0.9}))

(defn- coordinate-supply-order
  "Maintenance-materials procurement proposal draft. `:cost-estimate`
  drives the governor's high-cost escalation gate
  (`landsupport.governor/high-cost-threshold`)."
  [st {:keys [subject materials cost-estimate]}]
  (let [f (store/facility st subject)]
    {:summary    (str subject " 向け保守資材調達提案 (見積り=" cost-estimate ")")
     :rationale  (if f
                   (str "資材リスト " (pr-str materials) " の調達提案。")
                   "施設記録が見つかりません。")
     :cites      (if f [subject] [])
     :effect     :propose
     :op         :coordinate-supply-order
     :value      {:kind :supply-order-proposal :materials materials :cost-estimate cost-estimate}
     :stake      nil
     :confidence (if f 0.85 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :log-facility-record               (log-facility-record st request)
    :schedule-facility-maintenance     (schedule-facility-maintenance st request)
    :flag-structural-safety-concern    (flag-structural-safety-concern st request)
    :coordinate-supply-order           (coordinate-supply-order st request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :op op :value {} :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地上交通インフラ支援事業者(有料道路/橋梁/トンネル/駐車施設/バスターミナル)の"
       "運用調整エージェントの助言者です。与えられた事実のみに基づき、"
       "提案を1つだけEDNマップで返します。説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に :propose) "
       ":op(:log-facility-record|:schedule-facility-maintenance|"
       ":flag-structural-safety-concern|:coordinate-supply-order) "
       ":value(操作固有のペイロード) :confidence(0..1)。\n"
       "重要: あなたには構造安全性クリアランスを確定/認定/発行する権限が一切ありません。"
       "構造的懸念を検出した場合は必ず人間レビューへのエスカレーションとして報告し、"
       "決してクリアランスを確定したかのような文言を書かないこと。"
       "未登録/未検証の施設に対する要件を創作してもいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:facility (store/facility st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Land Transport Support
  Governor escalates/holds -- an LLM hiccup can never auto-commit a
  proposal, let alone finalize a structural-safety clearance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :value #(or % {})))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :op :noop :value {} :stake nil :confidence 0.0})))

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
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
