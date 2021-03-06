(ns donkey.services.oauth
  "Service implementations dealing with OAuth 2.0 authentication."
  (:use [donkey.auth.user-attributes :only [current-user]])
  (:require [authy.core :as authy]
            [cemerick.url :as curl]
            [clj-http.client :as http]
            [donkey.persistence.oauth :as op]
            [donkey.util.service :as service]
            [donkey.util.validators :as v]))

(defn- build-authy-server-info
  "Builds the server info to pass to authy."
  [server-info token-callback]
  (assoc (dissoc server-info :api-name)
    :token-callback token-callback))

(defn get-access-token
  "Receives an OAuth authorization code and obtains an access token."
  [{:keys [api-name] :as server-info} {:keys [code state]}]
  (v/validate-param :code code)
  (v/validate-param :state state)
  (let [username       (:username current-user)
        state-info     (op/retrieve-authorization-request-state state username)
        token-callback (partial op/store-access-token api-name username)]
    (authy/get-access-token (build-authy-server-info server-info token-callback) code)
    (service/success-response {:state_info state-info})))
