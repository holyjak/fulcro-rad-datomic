(ns com.fulcrologic.rad.database-adapters.datomic-cloud
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database-adapters.datomic-common :as common :refer [type-map]]
    [com.fulcrologic.rad.database-adapters.datomic-options :as do]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [select-keys-in-ns]]
    [datomic.client.api :as d]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn ref-entity->ident [db {:db/keys [ident id] :as ent}]
  (cond
    ident ident
    id (if-let [ident (:v (first (d/datoms db {:index      :eavt
                                               :components [id :db/ident]})))]
         ident
         ent)
    :else ent))

(defn replace-ref-types
  "dbc   the database to query
   refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   m     map returned from datomic pull containing the entity IDs you want to deref"
  [db refs arg]
  (walk/postwalk
    (fn [arg]
      (cond
        (and (map? arg) (some #(contains? refs %) (keys arg)))
        (reduce
          (fn [acc ref-k]
            (cond
              (and (get acc ref-k) (not (vector? (get acc ref-k))))
              (update acc ref-k (partial ref-entity->ident db))

              (and (get acc ref-k) (vector? (get acc ref-k)))
              (update acc ref-k #(mapv (partial ref-entity->ident db) %))

              :else acc))
          arg
          refs)
        :else arg))
    arg))

(defn pull-many
  "like Datomic pull, but takes a collection of ids and returns
   a collection of entity maps"
  ([db pull-spec ids]
   (let [lookup-ref?  (vector? (first ids))
         order-map    (if lookup-ref?
                        (into {} (map vector (map second ids) (range)))
                        (into {} (map vector ids (range))))
         sort-kw      (if lookup-ref? (ffirst ids) :db/id)
         missing-ref? (nil? (seq (filter #(= sort-kw %) pull-spec)))
         pull-spec    (if missing-ref?
                        (conj pull-spec sort-kw)
                        pull-spec)]
     (->> pull-spec
       (d/q '[:find (pull ?id pattern)
              :in $ [?id ...] pattern]
         db
         ids)
       (map first)
       (sort-by #(order-map (get % sort-kw)))
       vec))))

(defn pull-*
  "Will either call d/pull or d/pull-many depending on if the input is
  sequential or not.
  Optionally takes in a transform-fn, applies to individual result(s)."
  ([db pattern db-idents eid-or-eids]
   (->> (if (and (not (eql/ident? eid-or-eids)) (sequential? eid-or-eids))
          (pull-many db pattern eid-or-eids)
          (d/pull db pattern eid-or-eids))
     (replace-ref-types db db-idents)))
  ([db pattern ident-keywords eid-or-eids transform-fn]
   (let [result (pull-* db pattern ident-keywords eid-or-eids)]
     (if (sequential? result)
       (mapv transform-fn result)
       (transform-fn result)))))

(defn get-by-ids
  [db ids db-idents desired-output]
  (pull-* db desired-output db-idents ids))

(defn refresh-current-dbs!
  "Updates the database atoms in the given pathom env. This should be called after any mutation, since a mutation
   can have a mutation join for returning data."
  [env]
  (doseq [k (keys (get env do/connections))
          :let [conn    (get-in env [do/connections k])
                db-atom (get-in env [do/databases k])]]
    (reset! db-atom (d/db conn))))

(defn save-form!
  "Do all of the possible Datomic operations for the given form delta (save to all
   Datomic databases involved). If you include `:datomic/transact` in the `env`, then
   that function will be used to transact datoms instead of the default (Datomic) function."
  [{:datomic/keys [transact] :as env} {::form/keys [delta] :as save-params}]
  (let [schemas (common/schemas-for-delta env delta)
        result  (atom {:tempids {}})]
    (doseq [schema schemas
            :let [connection (-> env do/connections (get schema))
                  {:keys [tempid->string
                          tempid->generated-id
                          txn]} (common/delta->txn env schema delta)]]
      (when (log/may-log? :trace)
        (log/trace "Saving form delta" (with-out-str (pprint delta)))
        (log/trace "on schema" schema)
        (log/trace "Running txn\n" (with-out-str (pprint txn))))
      (if (and connection (seq txn))
        (try
          (let [database-atom   (get-in env [do/databases schema])
                tx!             (or transact d/transact)
                {:keys [tempids] :as tx-result} (tx! connection {:tx-data txn})
                tempid->real-id (into {}
                                  (map (fn [tempid] [tempid (get tempid->generated-id tempid
                                                              (get tempids (tempid->string tempid)))]))
                                  (keys tempid->string))]
            (when database-atom
              (reset! database-atom (d/db connection)))
            (swap! result update :tempids merge tempid->real-id))
          (catch Exception e
            (log/error e "Transaction failed!")
            {}))
        (log/error "Unable to save form. Either connection was missing in env, or txn was empty.")))
    @result))

(defn delete-entity!
  "Delete the given entity, if possible. `env` should contain the normal datomic middleware
   elements, and can also include `:datomic/transact` to override the function that is used
   to transact datoms."
  [{:datomic/keys [transact]
    ::attr/keys   [key->attribute] :as env} params]
  (enc/if-let [pk         (ffirst params)
               id         (get params pk)
               ident      [pk id]
               {:keys [::attr/schema]} (key->attribute pk)
               connection (-> env do/connections (get schema))
               txn        [[:db/retractEntity ident]]]
    (do
      (log/info "Deleting" ident)
      (let [database-atom (get-in env [do/databases schema])
            tx!           (or transact d/transact)]
        (tx! connection {:tx-data txn})
        (when database-atom
          (reset! database-atom (d/db connection)))
        {}))
    (log/warn "Datomic adapter failed to delete " params)))

(def suggested-logging-blacklist
  "A vector containing a list of namespace strings that generate a lot of debug noise when using Datomic. Can
  be added to Timbre's ns-blacklist to reduce logging overhead."
  ;; TODO - need to identify these for Cloud
  ["shadow.cljs.devtools.server.worker.impl"])

(defn verify-schema!
  "Validate that a database supports then named `schema`. This function finds all attributes
  that are declared on the schema, and checks that the Datomic representation of them
  meets minimum requirements for desired operation.
  This function throws an exception if a problem is found, and can be used in
  applications that manage their own schema to ensure that the database will
  operate correctly in RAD."
  [db schema all-attributes]
  (let [problems (common/schema-problems db schema all-attributes d/pull)]
    (when (seq problems)
      (doseq [p problems]
        (log/error p))
      (throw (ex-info "Validation Failed" {:schema schema})))))

(defn config->client [config]
  (if (= (:datomic/env config) :test)
    (:datomic/test-client config)
    (d/client (:datomic/client config))))

(defn ^:deprecated ensure-schema!
  "Use common/ensure-schema! instead."
  ([all-attributes config conn]
   (ensure-schema! all-attributes config {} conn))
  ([all-attributes {:datomic/keys [schema] :as config} schemas conn]
   (let [generator (get schemas schema :auto)]
     (cond
       (= :auto generator) (common/ensure-schema! (fn [c txn] (d/transact c {:tx-data txn})) conn schema all-attributes)
       (ifn? generator) (do
                          (log/info "Running custom schema function.")
                          (generator conn))
       :otherwise (log/info "Schema management disabled.")))))

(defn start-database!
  "Starts a Datomic database connection given the standard sub-element config described
  in `start-databases`. Typically use that function instead of this one.
  NOTE: This function relies on the attribute registry, which you must populate before
  calling this.
  `all-attributes
  * `:config` a map of k-v pairs for setting up a connection.
  * `schemas` a map from schema name to either :auto, :none, or (fn [conn]).
  Returns a migrated database connection."
  [all-attributes {:datomic/keys [schema database] :as config} schemas]
  (let [client (config->client config)
        _      (d/create-database client {:db-name database})
        conn   (d/connect client {:db-name database})]
    (when (= :auto (get schemas schema :auto))
      (common/ensure-schema! (fn [c txn] (d/transact c {:tx-data txn})) conn schema all-attributes))
    (verify-schema! (d/db conn) schema all-attributes)
    (log/info "Finished connecting to and migrating database for" schema)
    conn))

(defn adapt-external-database!
  "Adds necessary transactor functions and verifies schema of a Datomic database that is not
  under the control of this adapter, but is used by it."
  [conn schema all-attributes]
  (verify-schema! (d/db conn) schema all-attributes))

(defn start-databases
  "Start all of the databases described in config, using the schemas defined in schemas.

  * `config`:  a map that contains the key `do/databases`.
  * `schemas`:  a map whose keys are schema names, and whose values can be missing (or :auto) for

  automatic schema generation, a `(fn [schema-name conn] ...)` that updates the schema for schema-name
  on the database reachable via `conn`. You may omit `schemas` if automatic generation is being used
  everywhere.

  The `do/databases` entry in the config is a map with the following form:
  ```
  {:production-shard-1 {:datomic/schema :production
                        :datomic/client  {<client config map; see Datomic Cloud docs>}
                        :datomic/database \"prod\"}}
  ```
  The `:datomic/schema` is used to select the attributes that will appear in that database's schema.
  Returns a map whose keys are the database keys (i.e. `:production-shard-1`) and
  whose values are the live database connection.
  "
  ([all-attributes config]
   (start-databases all-attributes config {}))
  ([all-attributes config schemas]
   (reduce-kv
     (fn [m k v]
       (log/info "Starting database " k)
       (assoc m k (start-database! all-attributes v schemas)))
     {}
     (do/databases config))))

(defn entity-query
  [{:keys       [::attr/schema ::id-attribute]
    ::attr/keys [attributes]
    :as         env} input]
  (let [{native-id?  do/native-id?
         ::attr/keys [qualified-key]} id-attribute
        one? (not (sequential? input))]
    (enc/if-let [db           (some-> (get-in env [do/databases schema]) deref)
                 query        (get env ::default-query)
                 ids          (if one?
                                [(get input qualified-key)]
                                (into [] (keep #(get % qualified-key) input)))
                 ids          (if native-id?
                                ids
                                (mapv (fn [id] [qualified-key id]) ids))
                 enumerations (into #{}
                                (keep #(when (= :enum (::attr/type %))
                                         (::attr/qualified-key %)))
                                attributes)]
      (do
        (log/trace "Running" query "on entities with " qualified-key ":" ids)
        (let [result (get-by-ids db ids enumerations query)]
          (if one?
            (first result)
            result)))
      (do
        (log/info "Unable to complete query.")
        nil))))

(defn id-resolver
  "Generates a resolver from `id-attribute` to the `output-attributes`."
  [all-attributes
   {::attr/keys [qualified-key] :keys [::attr/schema :com.wsscode.pathom.connect/transform] :as id-attribute}
   output-attributes]
  (log/info "Building ID resolver for" qualified-key)
  (enc/if-let [_          id-attribute
               outputs    (attr/attributes->eql output-attributes)
               pull-query (common/pathom-query->datomic-query all-attributes outputs)]
    (let [wrap-resolve     (get id-attribute do/wrap-resolve (get id-attribute ::wrap-resolve))
          resolve-sym      (symbol
                             (str (namespace qualified-key))
                             (str (name qualified-key) "-resolver"))
          with-resolve-sym (fn [r]
                             (fn [env input]
                               (r (assoc env :com.wsscode.pathom.connect/sym resolve-sym) input)))]
      (log/debug "Computed output is" outputs)
      (log/debug "Datomic pull query to derive output is" pull-query)
      (cond-> {:com.wsscode.pathom.connect/sym     resolve-sym
               :com.wsscode.pathom.connect/output  outputs
               :com.wsscode.pathom.connect/batch?  true
               :com.wsscode.pathom.connect/resolve (cond-> (fn [{::attr/keys [key->attribute] :as env} input]
                                                             (->> (entity-query
                                                                    (assoc env
                                                                      ::attr/schema schema
                                                                      ::attr/attributes output-attributes
                                                                      ::id-attribute id-attribute
                                                                      ::default-query pull-query)
                                                                    input)
                                                               (common/datomic-result->pathom-result key->attribute outputs)
                                                               (auth/redact env)))
                                                     wrap-resolve (wrap-resolve)
                                                     :always (with-resolve-sym))
               :com.wsscode.pathom.connect/input   #{qualified-key}}
        transform transform))
    (do
      (log/error "Unable to generate id-resolver. "
        "Attribute was missing schema, or could not be found in the attribute registry: " qualified-key)
      nil)))

(defn generate-resolvers
  "Generate all of the resolvers that make sense for the given database config. This should be passed
  to your Pathom parser to register resolvers for each of your schemas."
  [attributes schema]
  (let [attributes            (filter #(= schema (::attr/schema %)) attributes)
        key->attribute        (attr/attribute-map attributes)
        entity-id->attributes (group-by ::k (mapcat (fn [attribute]
                                                      (map
                                                        (fn [id-key] (assoc attribute ::k id-key))
                                                        (get attribute ::attr/identities)))
                                              attributes))
        entity-resolvers      (reduce-kv
                                (fn [result k v]
                                  (enc/if-let [attr     (key->attribute k)
                                               resolver (id-resolver attributes attr v)]
                                    (conj result resolver)
                                    (do
                                      (log/error "Internal error generating resolver for ID key" k)
                                      result)))
                                []
                                entity-id->attributes)]
    entity-resolvers))

(defn mock-resolver-env
  "Returns a mock env that has the do/connections and do/databases keys that would be present in
  a properly-set-up pathom resolver `env` for a given single schema. This should be called *after*
  you have seeded data against a `connection` that goes with the given schema.
  * `schema` - A schema name
  * `connection` - A database connection that is connected to a database with that schema."
  [schema connection]
  {do/connections {schema connection}
   do/databases   {schema (atom (d/db connection))}})

(defn ^:deprecated pathom-plugin
  "Use datomic-common/pathom-plugin or wrap-env, depending on Pathom version.

  A pathom plugin that adds the necessary Datomic connections and databases to the pathom env for
  a given request. Requires a database-mapper, which is a
  `(fn [pathom-env] {schema-name connection})` for a given request.
  The resulting pathom-env available to all resolvers will then have:
  - `do/connections`: The result of database-mapper
  - `do/databases` A map from schema name to atoms holding a database. The atom is present so that
  a mutation that modifies the database can choose to update the snapshot of the db being used for the remaining
  resolvers.
  This plugin should run before (be listed after) most other plugins in the plugin chain since
  it adds connection details to the parsing env.
  "
  [database-mapper]
  (common/pathom-plugin database-mapper d/db))

(defn wrap-datomic-save
  "Form save middleware to accomplish Datomic saves.

   `handler` - Another middleware function to chain
   `addl-env` - Static (constant) things to add to the pathom-env on every call. Built-in
   support uses:
   ** `:datomic/transact` - The function to call to transact datoms. Overrides the normal Datomic transact
      and is useful for augmenting the behavior.
   "
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result (save-form! pathom-env params)]
       save-result)))
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result    (save-form! pathom-env params)
           handler-result (handler pathom-env)]
       (deep-merge save-result handler-result))))
  ([handler addl-env]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [env            (merge addl-env pathom-env)
           save-result    (save-form! env params)
           handler-result (handler env)]
       (deep-merge save-result handler-result)))))

(defn wrap-datomic-delete
  "Form delete middleware to accomplish datomic deletes.

   `handler` - Another middleware function to chain
   `addl-env` - Static (constant) things to add to the pathom-env on every call. Built-in
   support uses:
   ** `:datomic/transact` - The function to call to transact datoms. Overrides the normal Datomic transact
      and is useful for augmenting the behavior.
  "
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [local-result   (delete-entity! pathom-env params)
           handler-result (handler pathom-env)]
       (deep-merge handler-result local-result))))
  ([handler addl-env]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [env            (merge addl-env pathom-env)
           local-result   (delete-entity! env params)
           handler-result (handler env)]
       (deep-merge handler-result local-result))))
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (delete-entity! pathom-env params))))
