export interface FirebaseWebConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
  measurementId: string;
}

export const APP_ID = "276428506b1646179810c6bd6b559072";
export const CHANNEL_NAME = "just-chat-dfc03";
export const TOKEN_SERVER_URL = "https://agora-token-server-mauve.vercel.app/api/token";

export const AGORA_APP_ID = APP_ID;
export const AGORA_CHANNEL_NAME = CHANNEL_NAME;
export const AGORA_TOKEN_SERVER_URL = TOKEN_SERVER_URL;

export const FIREBASE_CONFIG: FirebaseWebConfig = {
  apiKey: "",
  authDomain: "",
  projectId: "",
  storageBucket: "",
  messagingSenderId: "",
  appId: "",
  measurementId: ""
};
