(ns donkey.services.metadata.apps
  (:use [clojure-commons.validators :only [validate-map]]
        [donkey.auth.user-attributes :only [current-user]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [throw+ try+]])
  (:require [cemerick.url :as curl]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]
            [donkey.clients.metadactyl :as metadactyl]
            [donkey.clients.osm :as osm]
            [donkey.persistence.jobs :as jp]
            [donkey.persistence.oauth :as op]
            [donkey.services.metadata.agave-apps :as aa]
            [donkey.services.metadata.de-apps :as da]
            [donkey.util.config :as config]
            [donkey.util.db :as db]
            [donkey.util.service :as service]
            [mescal.de :as agave])
  (:import [java.util UUID]))

(def ^:private uuid-regexes
  [#"^\p{XDigit}{8}(?:-\p{XDigit}{4}){3}-\p{XDigit}{12}$"
   #"^[at]\p{XDigit}{32}"])

(defn- is-uuid?
  [id]
  (some #(re-find % id) uuid-regexes))

(defn- count-jobs-of-types
  [job-types filter]
  (jp/count-jobs (:username current-user) job-types filter))

(defn- format-job
  [de-states de-apps agave-states {:keys [id] :as job}]
  (if (= (:job_type job) jp/agave-job-type)
    (aa/format-agave-job job (agave-states id))
    (da/format-de-job de-states de-apps job)))

(defn- list-all-jobs
  [agave limit offset sort-field sort-order filter]
  (let [user         (:username current-user)
        types        [jp/de-job-type jp/agave-job-type]
        jobs         (jp/list-jobs-of-types user limit offset sort-field sort-order filter types)
        grouped-jobs (group-by :job_type jobs)
        de-states    (da/load-de-job-states (grouped-jobs jp/de-job-type []))
        de-apps      (da/load-app-details (map :analysis_id de-states))
        agave-states (aa/load-agave-job-states agave (grouped-jobs jp/agave-job-type []))]
    (map (partial format-job de-states de-apps agave-states) jobs)))

(defn- update-job-status
  ([{:keys [id status end-date deleted]}]
     (jp/update-job id {:status   status
                        :end-date (db/timestamp-from-str (str end-date))
                        :deleted  deleted}))
  ([agave username prev-job-info status end-time]
     (aa/update-agave-job-status agave username prev-job-info status end-time)))

(defn- unrecognized-job-type
  [job-type]
  (throw+ {:error_code ce/ERR_ILLEGAL_ARGUMENT
           :argument   "job_type"
           :value      job-type}))

(defn- process-job
  ([agave-client job-id processing-fns]
     (process-job agave-client job-id (jp/get-job-by-external-id job-id) processing-fns))
  ([agave-client job-id job {:keys [process-agave-job process-de-job preprocess-job]
                             :or {preprocess-job identity}}]
     (condp = (:job_type job)
       nil               (service/not-found "job" job-id)
       jp/de-job-type    (process-de-job (preprocess-job job))
       jp/agave-job-type (process-agave-job agave-client (preprocess-job job))
       (unrecognized-job-type (:job_type job)))))

(defn- agave-authorization-uri
  [state-info]
  (let [username (:username current-user)
        state    (op/store-authorization-request username state-info)]
    (-> (curl/url (config/agave-oauth-base) "authorize")
        (assoc :query {:response_type "code"
                       :client_id     (config/agave-key)
                       :redirect-uri  (config/agave-redirect-uri)
                       :state         state})
        (str))))

(defn- agave-authorization-redirect
  [state-info]
  (throw+ {:error_code ce/ERR_TEMPORARILY_MOVED
           :location   (agave-authorization-uri state-info)}))

(defn- add-predicate
  [predicate f]
  (fn [& args]
    (when (predicate)
      (apply f args))))

(defprotocol AppLister
  "Used to list apps available to the Discovery Environment."
  (listAppGroups [_])
  (listApps [_ group-id params])
  (searchApps [_ search-term])
  (updateFavorites [_ app-id favorite?])
  (rateApp [_ app-id rating comment-id])
  (deleteRating [_ app-id])
  (getApp [_ app-id])
  (getAppDeployedComponents [_ app-id])
  (getAppDetails [_ app-id])
  (submitJob [_ workspace-id submission])
  (countJobs [_ filter])
  (listJobs [_ limit offset sort-field sort-order filter])
  (syncJobStatus [_ job])
  (populateJobsTable [_])
  (removeDeletedJobs [_])
  (updateJobStatus [_ username prev-status status end-time])
  (getJobParams [_ job-id])
  (getAppRerunInfo [_ job-id]))
;; AppLister

(deftype DeOnlyAppLister []
  AppLister

  (listAppGroups [_]
    (metadactyl/get-only-app-groups))

  (listApps [_ group-id params]
    (metadactyl/apps-in-group group-id params))

  (searchApps [_ search-term]
    (metadactyl/search-apps search-term))

  (updateFavorites [_ app-id favorite?]
    (metadactyl/update-favorites app-id favorite?))

  (rateApp [_ app-id rating comment-id]
    (metadactyl/rate-app app-id rating comment-id))

  (deleteRating [_ app-id]
    (metadactyl/delete-rating app-id))

  (getApp [_ app-id]
    (metadactyl/get-app app-id))

  (getAppDeployedComponents [_ app-id]
    (metadactyl/get-deployed-components-in-app app-id))

  (getAppDetails [_ app-id]
    (metadactyl/get-app-details app-id))

  (submitJob [_ workspace-id submission]
    (da/store-submitted-de-job (metadactyl/submit-job workspace-id submission)))

  (countJobs [_ filter]
    (count-jobs-of-types [jp/de-job-type] filter))

  (listJobs [_ limit offset sort-field sort-order filter]
    (da/list-de-jobs limit offset sort-field sort-order filter))

  (syncJobStatus [_ job]
    (when (= (:job_type job) jp/de-job-type)
      (da/sync-de-job-status job)))

  (populateJobsTable [_]
    (dorun (map da/store-de-job (osm/list-jobs))))

  (removeDeletedJobs [_]
    (da/remove-deleted-de-jobs))

  (updateJobStatus [_ username prev-status status end-time]
    (throw+ {:error_code ce/ERR_BAD_REQUEST
             :reason     "HPC_JOBS_DISABLED"}))

  (getJobParams [_ job-id]
    (da/get-de-job-params job-id))

  (getAppRerunInfo [_ job-id]
    (da/get-de-app-rerun-info job-id)))
;; DeOnlyAppLister

(deftype DeHpcAppLister [agave-client user-has-access-token?]
  AppLister

  (listAppGroups [_]
    (-> (metadactyl/get-only-app-groups)
        (update-in [:groups] conj (.hpcAppGroup agave-client))))

  (listApps [_ group-id params]
    (if (= group-id (:id (.hpcAppGroup agave-client)))
      (.listApps agave-client)
      (metadactyl/apps-in-group group-id params)))

  ;; TODO: modify to work with Agave.
  (searchApps [_ search-term]
    (let [de-apps  (metadactyl/search-apps search-term)
          hpc-apps (.searchPublicApps agave-client search-term)]
      {:template_count (apply + (map :template_count [de-apps hpc-apps]))
       :templates      (mapcat :templates [de-apps hpc-apps])}))

  (updateFavorites [_ app-id favorite?]
    (if (is-uuid? app-id)
      (metadactyl/update-favorites app-id favorite?)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "HPC apps cannot be marked as favorites"})))

  (rateApp [_ app-id rating comment-id]
    (if (is-uuid? app-id)
      (metadactyl/rate-app app-id rating comment-id)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "HPC apps cannot be rated"})))

  (deleteRating [_ app-id]
    (if (is-uuid? app-id)
      (metadactyl/delete-rating app-id)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "HPC apps cannot be rated"})))

  (getApp [_ app-id]
    (if (is-uuid? app-id)
      (metadactyl/get-app app-id)
      (.getApp agave-client app-id)))

  (getAppDeployedComponents [_ app-id]
    (if (is-uuid? app-id)
      (metadactyl/get-deployed-components-in-app app-id)
      {:deployed_components [(.getAppDeployedComponent agave-client app-id)]}))

  (getAppDetails [_ app-id]
    (if (is-uuid? app-id)
      (metadactyl/get-app-details app-id)
      (.getAppDetails agave-client app-id)))

  (submitJob [_ workspace-id submission]
    (if (is-uuid? (:analysis_id submission))
      (da/store-submitted-de-job (metadactyl/submit-job workspace-id submission))
      (aa/submit-agave-job agave-client submission)))

  (countJobs [_ filter]
    (count-jobs-of-types [jp/de-job-type jp/agave-job-type] filter))

  (listJobs [_ limit offset sort-field sort-order filter]
    (if (user-has-access-token?)
      (list-all-jobs agave-client limit offset sort-field sort-order filter)
      (da/list-de-jobs limit offset sort-field sort-order filter)))

  (syncJobStatus [_ job]
    (let [sync-agave (add-predicate user-has-access-token? aa/sync-agave-job-status)]
      (process-job agave-client (:id job) job
                   {:process-de-job    da/sync-de-job-status
                    :process-agave-job sync-agave})))

  (populateJobsTable [_]
    (dorun (map da/store-de-job (osm/list-jobs))))

  (removeDeletedJobs [_]
    (da/remove-deleted-de-jobs)
    (when (user-has-access-token?)
      (aa/remove-deleted-agave-jobs agave-client)))

  (updateJobStatus [_ username prev-job-info status end-time]
    (update-job-status agave-client username prev-job-info status end-time))

  ;; TODO: modify to work with Agave.
  (getJobParams [_ job-id]
    (process-job agave-client job-id
                 {:process-de-job    da/get-de-job-params
                  :process-agave-job aa/get-agave-job-params
                  :preprocess-job    :id}))

  ;; TODO: modify to work with Agave.
  (getAppRerunInfo [_ job-id]
    (process-job agave-client job-id
                 {:process-de-job    da/get-de-app-rerun-info
                  :process-agave-job aa/get-agave-app-rerun-info
                  :preprocess-job    :id})))
;; DeHpcAppLister

(defn- has-access-token
  [{:keys [api-name] :as server-info} username]
  (seq (op/get-access-token api-name username)))

(defn- get-access-token
  [{:keys [api-name] :as server-info} state-info username]
  (if-let [token-info (op/get-access-token api-name username)]
    (assoc (merge server-info token-info)
      :token-callback  (partial op/store-access-token api-name username)
      :reauth-callback (partial agave-authorization-redirect state-info))
    (agave-authorization-redirect state-info)))

(defn- get-de-hpc-app-lister
  [state-info username]
  (DeHpcAppLister. (agave/de-agave-client-v2
                    (config/agave-base-url)
                    (partial get-access-token (config/agave-oauth-settings) state-info username)
                    (config/agave-jobs-enabled)
                    (config/irods-home))
                   (partial has-access-token (config/agave-oauth-settings) username)))

(defn- get-app-lister
  ([]
     (get-app-lister ""))
  ([state-info]
     (get-app-lister state-info (:username current-user)))
  ([state-info username]
     (if (config/agave-enabled)
       (get-de-hpc-app-lister state-info username)
       (DeOnlyAppLister.))))

(defn- populate-jobs-table
  [app-lister]
  (let [username (:username current-user)]
    (transaction
     (when (zero? (jp/count-all-jobs username))
       (.populateJobsTable app-lister)))))

(defn get-only-app-groups
  []
  (service/success-response (.listAppGroups (get-app-lister "type=apps"))))

(defn apps-in-group
  [group-id params]
  (-> (get-app-lister (str "type=apps&app-category=" group-id))
      (.listApps group-id params)
      (service/success-response)))

(defn search-apps
  [{search-term :search}]
  (when (string/blank? search-term)
    (throw+ {:error_code ce/ERR_MISSING_QUERY_PARAMETER
             :param      :search}))
  (service/success-response (.searchApps (get-app-lister) search-term)))

(defn update-favorites
  [body]
  (let [request (service/decode-json body)]
    (.updateFavorites (get-app-lister)
                      (service/required-field request :analysis_id)
                      (service/required-field request :user_favorite))))

(defn rate-app
  [body]
  (let [request (service/decode-json body)]
    (.rateApp (get-app-lister)
              (service/required-field request :analysis_id)
              (service/required-field request :rating)
              (service/required-field request :comment_id))))

(defn delete-rating
  [body]
  (let [request (service/decode-json body)]
    (.deleteRating (get-app-lister) (service/required-field request :analysis_id))))

(defn get-app
  [app-id]
  (service/success-response (.getApp (get-app-lister) app-id)))

(defn get-deployed-components-in-app
  [app-id]
  (service/success-response (.getAppDeployedComponents (get-app-lister) app-id)))

(defn get-app-details
  [app-id]
  (service/success-response (.getAppDetails (get-app-lister) app-id)))

(defn submit-job
  [workspace-id body]
  (service/success-response
   (.submitJob (get-app-lister) workspace-id (service/decode-json body))))

(defn list-jobs
  [{:keys [limit offset sort-field sort-order filter]
    :or   {limit      "0"
           offset     "0"
           sort-field :startdate
           sort-order :desc}}]
  (let [limit      (Long/parseLong limit)
        offset     (Long/parseLong offset)
        sort-field (keyword sort-field)
        sort-order (keyword sort-order)
        app-lister (get-app-lister)
        filter     (when-not (nil? filter) (service/decode-json filter))]
    (populate-jobs-table app-lister)
    (.removeDeletedJobs app-lister)
    (service/success-response
     {:analyses  (.listJobs app-lister limit offset sort-field sort-order filter)
      :timestamp (str (System/currentTimeMillis))
      :total     (.countJobs app-lister filter)})))

(defn update-de-job-status
  [id status end-date]
  (update-job-status {:id       id
                      :status   status
                      :end-date end-date}))

(defn update-agave-job-status
  [uuid status end-time]
  (let [{:keys [username] :as job} (jp/get-job-by-id (UUID/fromString uuid))]
    (service/assert-found job "job" uuid)
    (service/assert-valid (= jp/agave-job-type (:job_type job)) "job" uuid "is not an HPC job")
    (.updateJobStatus (get-app-lister "" username) username job status end-time)))

(defn- sync-job-status
  [job]
  (try+
   (log/debug "synchronizing the job status for" (:id job))
   (.syncJobStatus (get-app-lister "" (:username job)) job)
   (catch Object e
     (log/error e "unable to sync the job status for job" (:id job)))))

(defn sync-job-statuses
  []
  (dorun (map sync-job-status (jp/list-incomplete-jobs))))

(defn- log-already-deleted-jobs
  [ids]
  (let [jobs-by-id (into {} (map (juxt :id identity) (jp/list-jobs-to-delete ids)))
        log-it     (fn [desc id] (log/warn "attempt to delete" desc "job" id "ignored"))]
    (dorun (map (fn [id] (cond (nil? (jobs-by-id id))            (log-it "missing" id)
                               (get-in jobs-by-id [id :deleted]) (log-it "deleted" id)))
                ids))))

(defn delete-jobs
  [body]
  (let [body (service/decode-json body)
        _    (validate-map body {:executions vector?})
        ids  (set (:executions body))]
    (log-already-deleted-jobs ids)
    (jp/delete-jobs ids)
    (service/success-response)))

(defn- validate-job-existence
  [id]
  (when-not (jp/get-job-by-external-id id)
    (service/not-found "job" id)))

(defn- validate-job-update
  [body]
  (let [supported-fields #{:name :description}
        invalid-fields   (remove supported-fields (keys body))]
    (when (seq invalid-fields)
      (throw+ {:error_code ce/ERR_BAD_OR_MISSING_FIELD
               :reason     (str "unrecognized fields: " invalid-fields)}))))

(defn update-job
  [id body]
  (let [body (service/decode-json body)]
    (validate-job-existence id)
    (validate-job-update body)
    (jp/update-job id body)))

(defn get-property-values
  [job-id]
  (service/success-response (.getJobParams (get-app-lister) job-id)))

(defn get-app-rerun-info
  [job-id]
  (service/success-response (.getAppRerunInfo (get-app-lister) job-id)))
