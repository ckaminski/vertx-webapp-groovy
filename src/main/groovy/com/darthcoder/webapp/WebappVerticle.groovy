package com.darthcoder.webapp 

import io.vertx.lang.groovy.GroovyVerticle
import io.vertx.groovy.ext.web.Router
import io.vertx.groovy.ext.web.handler.CookieHandler
import io.vertx.groovy.ext.web.handler.SessionHandler
import io.vertx.groovy.ext.web.handler.UserSessionHandler;
import io.vertx.groovy.ext.web.handler.BodyHandler
import io.vertx.groovy.ext.web.handler.RedirectAuthHandler
import io.vertx.groovy.ext.web.handler.StaticHandler
import io.vertx.groovy.ext.web.handler.TimeoutHandler
import io.vertx.groovy.ext.web.handler.FormLoginHandler
import io.vertx.groovy.ext.web.handler.sockjs.SockJSHandler
import io.vertx.groovy.ext.web.sstore.ClusteredSessionStore
import io.vertx.groovy.ext.web.sstore.LocalSessionStore
import io.vertx.core.json.JsonObject
import io.vertx.groovy.ext.auth.jdbc.JDBCAuth
import io.vertx.groovy.ext.jdbc.JDBCClient
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.groovy.ext.web.templ.*
import io.vertx.groovy.ext.web.handler.TemplateHandler

class WebappVerticle extends GroovyVerticle {
  def startupConfig
  def sessionStore
  def httpServer 
  def jdbcClient
  def authProvider
  def mailer
  def uFactory
  static Logger log = LoggerFactory.getLogger(WebappVerticle.class)
  TemplateEngine templEngine = MVELTemplateEngine.create()
  TemplateHandler templHandler = TemplateHandler.create(templEngine)

  def websocketHandler = { ws ->
      ws.handler(ws.&writeMessage)
  }

  private configureAuthProvider() {
    jdbcClient = JDBCClient.createShared(vertx, startupConfig['jdbc-config'])
    authProvider = JDBCAuth.create(jdbcClient)
    authProvider.setAuthenticationQuery("select password, password_salt from user_accounts where username = ?")
      .setPermissionsQuery("select perm from user_roles_perms rp, user_roles ur where ur.username = ? and ur.role = rp.role")
      .setRolesQuery("select role from user_roles where username = ?")
  }

  def getBaseUri(request) {
    def uri = request.absoluteURI();
    def baseuri = (uri =~ /((https?:\/\/)([^:^\/]*)(:\d*)?)/)
    def resStr = baseuri[0]
    return resStr[0]
  }

  def registerUser = { context ->
  	def attrs = context?.request()?.formAttributes()
  	def uname = attrs.username

    def strBaseUri = getBaseUri(context?.request())

    log.debug("Attempting to register user ${uname}")
    uFactory.newUser(uname, { res, uuid ->
      context.put("baseuri", strBaseUri)
      if (res.succeeded() ) {
        context.put("username", uname)
        context.put("uuid", uuid)
        templEngine.render(context, "templates/new_user_email.templ", { body ->
          println "Mailing ${uname} my login Id ${uuid}"
          def body_str = body?.result()?.toString("UTF-8")
          mailer.sendMessage(from: "admin@example.com", to: uname, htmlBody: body_str, { });
        })
        templEngine.render(context, "templates/new_user.templ", { body ->
          def body_str = body?.result()?.toString("UTF-8")

          println ("BodyStr: ${body_str}")
          context.response().setChunked(true)
          context.response().write(body_str)
          context.response().end()
        })
      } else if ( res.cause() =~ /duplicate key value/ ) { 
      	println("we were passed uuid [${uuid}]")
        uFactory.generatePasswordResetCode(uname, { respw, uuid2 ->
          println "Called into generatePassword callback new uuid ${uuid2}"
          if ( !respw.succeeded() ) println( respw.cause().toString())
          templEngine.render(context, "templates/new_password.templ", { body ->
            def body_str = body?.result()?.toString("UTF-8")

            println ("BodyStr: ${body_str}")
            context.response().setChunked(true)
            context.response().write(body_str)
            context.response().end()
          })
          println("exiting generatePassword")
        })
      } else { 
      }
     })

    /*context.response().setChunked(true)
    context.response().write("<html>this is /register</html>")
    context.response().end()
    */
  }
  def resetPassword = { context -> 
  	if (context.request().getParam('uuid') != null) {
  		// set password based on form variables. 
  	} else { 
  		// Display reset password page
  	}
  }

  def createRouter(vertx) { 
     // Simple auth service which uses a properties file for user/role info
    //authProvider = ShiroAuthProvider.create(vertx, ShiroAuthRealmType.PROPERTIES, [:])
    try { 
      sessionStore = LocalSessionStore.create(vertx)
      //sessionStore = ClusteredSessionStore.create(vertx)
    } catch ( IllegalStateException ex ) { 
    }

    def router = Router.router(vertx)

    // We need cookies, sessions and request bodies
    router.route().handler(TimeoutHandler.create(5000))
    router.route().handler(CookieHandler.create())
    router.route().handler(SessionHandler.create(sessionStore))
    router.route().handler(UserSessionHandler.create(authProvider))
    router.route().handler(BodyHandler.create())

    // Any requests to URI starting '/private/' require login
    router.route("/api/*").handler(RedirectAuthHandler.create(authProvider, "/index.html"))

    // Any requests to URI starting '/private/' require login
    router.route("/private/*").handler(RedirectAuthHandler.create(authProvider, "/index.html"))

    // Serve the static private pages from directory 'private'
    router.route("/private/*").handler(StaticHandler.create().setCachingEnabled(false).setWebRoot("private"))

    // Map to template handler 
    //router.route("/templates/").handler(templHandler)

    // Handles the actual login
    def formHandler = FormLoginHandler.create(authProvider)
    router.route("/loginhandler").handler({ context -> 
    		def session = context.session() 
    		if ( session.get("return_url") == null ) session.put("return_url", "/private/index.html") 
    		formHandler.handle(context)
    	})

    router.route('/passwdreset/:uuid').handler(resetPassword)
    router.route('/passwdreset').handler(resetPassword)
    router.route('/registerhandler').handler(registerUser)

    // Implement logout
    router.route("/logout").handler({ context ->
      context.session().destroy()
      // Redirect back to the index page
      context.response().putHeader("location", "/").setStatusCode(302).end()
    })

    // Serve the non private static pages
    router.route().handler(StaticHandler.create()) 

    return router
  }

  public void start() {
    startupConfig = context.config()
    mailer = new Emailer(vertx, startupConfig['mail-config'])

    uFactory = new UserFactory(vertx, startupConfig['jdbc-config'])
    
    configureAuthProvider()

    def router = createRouter(vertx)
    httpServer = vertx.createHttpServer(startupConfig['http-config'])
    httpServer.websocketHandler(websocketHandler)

    /*if ( httpconfig.map['ssl'] == true ) { 
        httpServer.with { 
          setSSL((boolean)true)
          setKeyStorePath(httpconfig.map['keystore']['path'] )
          setKeyStorePassword(httpconfig.map['keystore']['password'])
        }
        
    }*/

    httpServer.requestHandler(router.&accept).listen() //httpconfig.listenPort)
  } 
}

