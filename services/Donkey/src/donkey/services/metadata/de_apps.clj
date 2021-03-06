(ns donkey.services.metadata.de-apps
  (:use [clojure-commons.validators :only [validate-map]]
        [donkey.auth.user-attributes :only [current-user]])
  (:require [cemerick.url :as curl]
            [clojure.tools.logging :as log]
            [clojure-commons.file-utils :as ft]
            [donkey.clients.jex-events :as jex-events]
            [donkey.clients.metadactyl :as metadactyl]
            [donkey.clients.notifications :as dn]
            [donkey.persistence.apps :as ap]
            [donkey.persistence.jobs :as jp]
            [donkey.services.metadata.property-values :as property-values]
            [donkey.services.metadata.util :as mu]
            [donkey.util.config :as config]
            [donkey.util.db :as db]
            [donkey.util.service :as service]
            [donkey.util.time :as time-utils])
  (:import [java.util UUID]))

(defn- get-end-date
  [{:keys [status completion_date now_date]}]
  (case status
    jp/failed-status    (db/timestamp-from-str now_date)
    jp/completed-status (db/timestamp-from-str completion_date)
    nil))

(defn- de-job-callback-url
  []
  (str (curl/url (config/donkey-base-url) "callbacks" "de-job")))

(defn- prepare-submission
  [submission job-id]
  (assoc (mu/update-submission-result-folder submission (ft/build-result-folder-path submission))
    :uuid     (str job-id)
    :callback (de-job-callback-url)))

(defn submit-job
  [submission]
  (let [submission (prepare-submission submission (UUID/randomUUID))
        uuid       (:uuid (metadactyl/submit-job submission))
        job-id     ((comp :job-id first) (jp/get-job-steps-by-external-id uuid))
        job        (jp/get-job-by-id job-id)]
    (mu/send-job-status-notification job jp/submitted-status nil)
    {:id         (:id job)
     :name       (:job-name job)
     :status     (:status job)
     :start-date (db/millis-from-timestamp (:start-date job))}))

(defn submit-job-step
  [submission]
  (->> (prepare-submission submission (UUID/randomUUID))
       (metadactyl/submit-job)
       (:uuid)))

(defn load-app-details
  [ids]
  (into {} (map (juxt (comp str :id) identity)
                (ap/load-app-details ids))))

(defn get-job-step-status
  [id]
  (when-let [step (jex-events/get-job-state id)]
    {:status  (:status step)
     :enddate (:completion_date step)}))

(defn update-job-status
  "Updates the status of a job. If this function is called then Agave jobs are disabled, so
   there will always be only one job step."
  [username job job-step status end-time]
  (when-not (= (:status job-step) status)
    (jp/update-job-step (:id job) (:external-id job-step) status end-time)
    (jp/update-job (:id job) status end-time)
    (mu/send-job-status-notification job status end-time)))

(defn sync-job-status
  [{:keys [id] :as job}]
  (let [steps     (jp/list-job-steps id)
        _         (assert (= 1 (count steps)))
        step      (first steps)
        step-info (get-job-step-status (:external-id step))
        status    (:status step-info)
        end-time  (db/timestamp-from-str (:enddate step-info))]
    (jp/update-job-step-number id 1 {status status :end-time end-time})
    (jp/update-job id status end-time)))
