(ns landtransport.facts
  "Per-jurisdiction land-transport-support regulatory catalog -- the
  G2-style spec-basis table the Land Transport Support Governor checks
  every `:safety-scope/verify` proposal against ('did the advisor cite
  an OFFICIAL public source for this jurisdiction's toll-road / bus-
  terminal / vehicle-recovery safety-scope and licensing requirements,
  or did it invent one?').

  Each entry below is a REAL jurisdiction with a REAL land-transport-
  support regulatory regime: Japan's 国土交通省 (MLIT, toll-road
  concessions under 道路整備特別措置法, the Road Improvement Special
  Measures Act) alongside 都道府県公安委員会 (prefectural public safety
  commissions, vehicle-recovery/towing licensing); the US state
  Department of Transportation toll-authority statutes alongside
  state-licensed towing/recovery programs (e.g. California's Consumer
  Automotive Recovery Program); and the UK's DVSA-recognized vehicle-
  recovery operator scheme alongside UK toll-concession frameworks. The
  required-evidence set (toll plaza / bus terminal safety-scope
  registration, vehicle-recovery operator licensing record, multi-
  carrier terminal-slot booking evidence) mirrors the safety-scope,
  recovery-operator-licensing and multi-carrier-booking evidence a
  regulator actually demands before a toll-lane dispatch, a recovery
  job, or a terminal-slot operation is authorized.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. This R0 seeds
  exactly THREE jurisdictions (JPN/USA/GBR) -- a starting catalog, not
  a survey of all ~194 jurisdictions.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the land-transport-
  support evidence set (toll plaza / bus terminal safety-scope
  registration, vehicle-recovery operator licensing record, multi-
  carrier terminal-slot booking evidence); `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any `:safety-scope/verify` proposal can commit."
  {"JPN" {:name "JPN"
          :owner-authority "国土交通省 (toll roads) / 都道府県公安委員会 (towing/recovery licensing)"
          :legal-basis "道路整備特別措置法 (Road Improvement Special Measures Act, toll-road concessions)"
          :provenance "https://www.mlit.go.jp/"
          :required-evidence ["toll plaza / bus terminal safety-scope registration"
                              "vehicle-recovery operator licensing record"
                              "multi-carrier terminal-slot booking evidence"]}
   "USA" {:name "USA"
          :owner-authority "State Department of Transportation (toll authority) / State DMV or equivalent (towing licensing)"
          :legal-basis "State DOT toll-authority statutes; state-licensed towing/recovery programs (e.g. California's Consumer Automotive Recovery Program)"
          :provenance "https://www.transportation.gov/"
          :required-evidence ["toll plaza / bus terminal safety-scope registration"
                              "vehicle-recovery operator licensing record"
                              "multi-carrier terminal-slot booking evidence"]}
   "GBR" {:name "GBR"
          :owner-authority "Highways England / DVSA (Driver and Vehicle Standards Agency, recovery operator recognition)"
          :legal-basis "DVSA-recognized vehicle-recovery operator scheme; UK toll-concession frameworks"
          :provenance "https://www.gov.uk/government/organisations/driver-and-vehicle-standards-agency"
          :required-evidence ["toll plaza / bus terminal safety-scope registration"
                              "vehicle-recovery operator licensing record"
                              "multi-carrier terminal-slot booking evidence"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to verify a safety
  scope, authorize a dispatch or publish a reconciliation record on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5221 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `landtransport.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
