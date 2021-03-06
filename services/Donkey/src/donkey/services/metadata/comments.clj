(ns donkey.services.metadata.comments
  (:use [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clojure-commons.error-codes :as err]
            [donkey.auth.user-attributes :as user]
            [donkey.clients.data-info :as data]
            [donkey.persistence.metadata :as db]
            [donkey.util.service :as svc]
            [donkey.util.validators :as valid])
  (:import [com.fasterxml.jackson.core JsonParseException]))


(defn- extract-comment-id
  [entry-id comment-id-text]
  (try+
    (let [comment-id (valid/extract-uri-uuid comment-id-text)]
      (when-not (db/comment-on? comment-id entry-id)
        (throw+ {:error_code err/ERR_NOT_FOUND}))
      comment-id)))


(defn- extract-entry-id
  [user entry-id-txt]
  (try+
    (let [entry-id (valid/extract-uri-uuid entry-id-txt)]
      (data/validate-uuid-accessible user entry-id)
      entry-id)
    (catch [:error_code err/ERR_DOES_NOT_EXIST] _ (throw+ {:error_code err/ERR_NOT_FOUND}))))


(defn- extract-retracted
  [retracted-txt]
  (when-not retracted-txt
    (throw+ {:error_code err/ERR_MISSING_QUERY_PARAMETER :parameter "retracted"}))
  (if (coll? retracted-txt)
    (let [vals (set (map str/lower-case retracted-txt))]
      (when (> (count vals) 1) (throw+ {:error_code err/ERR_CONFLICTING_QUERY_PARAMETER_VALUES
                                        :parameter  "retracted"
                                        :values     vals}))
      (extract-retracted (first vals)))
    (case (str/lower-case retracted-txt)
      "true"  true
      "false" false
      (throw+ {:error_code err/ERR_BAD_QUERY_PARAMETER
               :parameter  "retracted"
               :value      retracted-txt}))))


(defn- prepare-comment
  [comment]
  (-> comment
    (dissoc :retracted_by)
    (assoc :post_time (.getTime (:post_time comment)))))


(defn- read-body
  [stream]
  (try+
    (slurp stream)
    (catch OutOfMemoryError _
      (throw+ {:error_code err/ERR_REQUEST_BODY_TOO_LARGE}))))


(defn add-comment
  "Adds a comment to an filesystem entry.

   Parameters:
     entry-id - the `entry-id` from the request. This should be the UUID corresponding to the entry
                being commented on
     body - the request body. It should be a JSON document containing the comment"
  [entry-id body]
  (try+
    (let [user     (:shortUsername user/current-user)
          entry-id (extract-entry-id user entry-id)
          comment  (-> body read-body (json/parse-string true) :comment)
          tgt-type (data/resolve-data-type entry-id)]
      (when-not comment (throw+ {:error_code err/ERR_INVALID_JSON}))
      (svc/create-response {:comment (->> comment
                                       (db/insert-comment user entry-id tgt-type)
                                       prepare-comment)}))
    (catch JsonParseException _ (throw+ {:error_code err/ERR_INVALID_JSON}))))


(defn list-comments
  [entry-id]
  "Returns a list of comments attached to a given filesystem entry.

   Parameters:
     entry-id - the `entry-id` from the request. This should be the UUID corresponding to the entry
                being inspected"
   (let [entry-id (extract-entry-id (:shortUsername user/current-user) entry-id)
         comments (map prepare-comment (db/select-all-comments entry-id))]
     (svc/success-response {:comments comments})))


(defn update-retract-status
  [entry-id comment-id retracted]
  "Changes the retraction status for a given comment.

   Parameters:
     entry-id - the `entry-id` from the request. This should be the UUID corresponding to the entry
                owning the comment being modified
     comment-id - the comment-id from the request. This should be the UUID corresponding to the
                  comment being modified
     retracted - the `retracted` query parameter. This should be either `true` or `false`."
  (let [user        (:shortUsername user/current-user)
        entry-id    (extract-entry-id user entry-id)
        comment-id  (extract-comment-id entry-id comment-id)
        retracting? (extract-retracted retracted)
        entry-path  (:path (data/stat-by-uuid user entry-id))
        owns-entry? (and entry-path (data/owns? user entry-path))
        comment     (db/select-comment comment-id)]
    (if retracting?
      (if (or owns-entry? (= user (:commenter comment)))
        (db/retract-comment comment-id user)
        (throw+ {:error_code err/ERR_NOT_OWNER :reason "doesn't own either entry or comment"}))
      (if (= user (:retracted_by comment))
        (db/readmit-comment comment-id)
        (throw+ {:error_code err/ERR_NOT_OWNER :reason "wasn't retractor"})))
    (svc/success-response)))
