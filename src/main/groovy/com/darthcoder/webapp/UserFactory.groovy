package com.darthcoder.webapp 

import io.vertx.lang.groovy.GroovyVerticle
import io.vertx.groovy.ext.web.Router
import io.vertx.groovy.ext.web.handler.CookieHandler
import io.vertx.groovy.ext.web.handler.SessionHandler
import io.vertx.groovy.ext.web.handler.UserSessionHandler;
import io.vertx.groovy.ext.web.handler.BodyHandler
import io.vertx.groovy.ext.web.handler.RedirectAuthHandler
import io.vertx.groovy.ext.web.handler.StaticHandler
import io.vertx.groovy.ext.web.handler.FormLoginHandler
import io.vertx.groovy.ext.web.handler.sockjs.SockJSHandler
import io.vertx.groovy.ext.web.sstore.ClusteredSessionStore
import io.vertx.core.json.JsonObject
import io.vertx.groovy.ext.auth.jdbc.JDBCAuth
import io.vertx.groovy.ext.jdbc.JDBCClient
import java.util.UUID

class UserFactory { 
	private JDBCClient jClient 

  final String QUERYSTR_NEWUSER = "INSERT INTO user_accounts (username, forgotten_password_key, email_address) VALUES (?, ?, ?)" 
  final String QUERYSTR_REGENPWCODE = "UPDATE user_accounts SET forgotten_password_key = ? WHERE username = ?"

	UserFactory(vertx, config) { 
		jClient = JDBCClient.createShared(vertx, config)
	}

	private doInsert(query, params, handler) { 
    jClient.getConnection({ res -> 
    	  if (res.succeeded() ) { 
          def conn = res.result() 
          println "Running insert query \'${query}\'"
          conn.updateWithParams(query, params, { res2 ->
          	  conn.close()
              handler(res2)
          	})
    	  }
    	})
	}
	public generatePasswordResetCode(String emailStr, next) { 
    def forgotuuid = UUID.randomUUID().toString()
    println("regenerating password code: ${forgotuuid}")
    doInsert(QUERYSTR_REGENPWCODE, [forgotuuid, emailStr], { res -> next(res, forgotuuid)})
	}
  
  public newUser(String emailStr, next) { 
    def forgotuuid = UUID.randomUUID().toString()
    def resultHandler = { res -> 
    	println "New user created! ${emailStr}" 
    	next(res, forgotuuid)
    }
    doInsert(QUERYSTR_NEWUSER, [emailStr, forgotuuid, emailStr], resultHandler)
  }

}
