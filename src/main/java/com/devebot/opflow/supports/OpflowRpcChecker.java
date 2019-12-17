package com.devebot.opflow.supports;

import com.devebot.opflow.OpflowUtil;

/**
 *
 * @author acegik
 */
public interface OpflowRpcChecker {

    Pong send(Ping info);
    
    public static class Ping {
        
    }
    
    public static class Pong {
        
    }
    
    public static class Info {
        private String status = "";

        public Info (Pong pong) {
            this.status = "ok";
        }
        
        public Info (Exception exception) {
            this.status = "failed";
        }
        
        @Override
        public String toString() {
            String summary;
            switch (status) {
                case "ok":
                    summary = "The connection is ok";
                    break;
                case "failed":
                    summary = "The workers have not been started or the parameters mismatched";
                    break;
                default:
                    summary = "Unknown error";
                    break;
            }
            return OpflowUtil.buildMap()
                    .put("status", status)
                    .put("summary", summary)
                    .toString();
        }
    }
}