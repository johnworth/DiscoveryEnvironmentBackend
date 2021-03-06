(ns metadactyl.service.app-metadata
  "DE app metadata services."
  (:use [clojure.java.io :only [reader]]
        [clojure-commons.validators]
        [kameleon.app-groups :only [add-app-to-category
                                    decategorize-app
                                    get-app-subcategory-id
                                    remove-app-from-category]]
        [kameleon.queries :only [get-existing-user-id]]
        [kameleon.uuids :only [uuidify]]
        [metadactyl.app-validation :only [app-publishable?]]
        [metadactyl.user :only [current-user]]
        [metadactyl.util.config :only [workspace-beta-app-category-id
                                       workspace-favorites-app-group-index]]
        [metadactyl.util.service :only [build-url success-response]]
        [metadactyl.validation :only [verify-app-ownership]]
        [metadactyl.workspace :only [get-workspace]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [throw+]])
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure-commons.error-codes :as ce]
            [metadactyl.persistence.app-metadata :as amp]
            [metadactyl.translations.app-metadata :as atx]
            [metadactyl.util.config :as config]))

(defn- get-valid-user-id
  "Gets the user ID for the given username, or throws an error if that username is not found."
  [username]
  (let [user-id (get-existing-user-id username)]
    (when (nil? user-id)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     (str "No user found for username " username)}))
    user-id))

(defn- validate-app-existence
  "Verifies that apps exist."
  [app-id]
  (amp/get-app app-id))

(defn relabel-app
  "This service allows labels to be updated in any app, whether or not the app has been submitted
   for public use."
  [body]
  (verify-app-ownership (validate-app-existence (:id body)))
  (transaction (amp/update-app-labels body))
  (success-response))

(defn- validate-app-ownership
  "Verifies that a user owns an app."
  [username app-id]
  (when-not (every? (partial = username) (amp/app-accessible-by app-id))
    (throw+ {:error_code ce/ERR_BAD_REQUEST
             :reason     (str username " does not own app " app-id)})))

(defn- validate-deletion-request
  "Validates an app deletion request."
  [req]
  (when (empty? (:app_ids req))
    (throw+ {:error_code ce/ERR_BAD_REQUEST
             :reason     "no app identifiers provided"}))
  (when (and (nil? (:username current-user)) (not (:root_deletion_request req)))
    (throw+ {:error_code ce/ERR_BAD_REQUEST
             :reason     "no username provided for non-root deletion request"}))
  (dorun (map validate-app-existence (:app_ids req)))
  (when-not (:root_deletion_request req)
    (dorun (map (partial validate-app-ownership (:username current-user)) (:app_ids req)))))

(defn permanently-delete-apps
  "This service removes apps from the database rather than merely marking them as deleted."
  [req]
  (validate-deletion-request req)
  (transaction
    (dorun (map amp/permanently-delete-app (:app_ids req)))
    (amp/remove-workflow-map-orphans))
  nil)

(defn delete-apps
  "This service marks existing apps as deleted in the database."
  [req]
  (validate-deletion-request req)
  (transaction (dorun (map amp/delete-app (:app_ids req))))
  {})

(defn delete-app
  "This service marks an existing app as deleted in the database."
  [app-id]
  (validate-app-existence app-id)
  (validate-app-ownership (:username current-user) app-id)
  (amp/delete-app app-id)
  {})

(defn preview-command-line
  "This service sends a command-line preview request to the JEX."
  [body]
  (let [jex-req (atx/template-cli-preview-req body)]
    (cheshire/decode-stream
     ((comp reader :body)
      (client/post
       (build-url (config/jex-base-url) "arg-preview")
       {:body             (cheshire/encode jex-req)
        :content-type     :json
        :as               :stream}))
     true)))

(defn rate-app
  "Adds or updates a user's rating and comment ID for the given app. The request must contain either
   the rating or the comment ID, and the rating must be between 1 and 5, inclusive."
  [app-id {:keys [rating comment_id] :as request}]
  (validate-app-existence app-id)
  (let [user-id (get-valid-user-id (:username current-user))]
    (when (and (nil? rating) (nil? comment_id))
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     (str "No rating or comment ID given")}))
    (when (or (> 1 rating) (> rating 5))
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     (str "Rating must be an integer between 1 and 5 inclusive."
                                " Invalid rating (" rating ") for App ID " app-id)}))
    (amp/rate-app app-id user-id request)
    (amp/get-app-avg-rating app-id)))

(defn delete-app-rating
  "Removes a user's rating and comment ID for the given app."
  [app-id]
  (validate-app-existence app-id)
  (let [user-id (get-valid-user-id (:username current-user))]
    (amp/delete-app-rating app-id user-id)
    (amp/get-app-avg-rating app-id)))

(defn- get-favorite-category-id
  "Gets the current user's Favorites category ID."
  []
  (get-app-subcategory-id
    (:root_category_id (get-workspace))
    (workspace-favorites-app-group-index)))

(defn add-app-favorite
  "Adds the given app to the current user's favorites list."
  [app-id]
  (let [app (amp/get-app app-id)
        fav-category-id (get-favorite-category-id)]
    (add-app-to-category app-id fav-category-id))
  nil)

(defn remove-app-favorite
  "Removes the given app from the current user's favorites list."
  [app-id]
  (let [app (amp/get-app app-id)
        fav-category-id (get-favorite-category-id)]
  (remove-app-from-category app-id fav-category-id))
  nil)

(defn- publish-app
  [{app-id :id :keys [references categories] :as app}]
  (transaction
    (amp/update-app app true)
    (amp/set-app-references app-id references)
    (amp/set-app-suggested-categories app-id categories)
    (decategorize-app app-id)
    (add-app-to-category app-id (uuidify (workspace-beta-app-category-id))))
  nil)

(defn make-app-public
  [{app-id :id :as app}]
  (verify-app-ownership (validate-app-existence app-id))
  (let [[publishable? reason] (app-publishable? app-id)]
    (if publishable?
      (publish-app app)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     reason}))))

(defn get-app
  "This service obtains an app description that can be used to build a job submission form in
   the user interface."
  [app-id]
  (->> (amp/get-app app-id)
       (success-response)))
