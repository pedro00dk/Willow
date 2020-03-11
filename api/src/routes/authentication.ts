import cookieParser from 'cookie-parser'
import cookieSession from 'cookie-session'
import express from 'express'
import passport from 'passport'
import GoogleOAuth from 'passport-google-oauth20'
import { Action, User } from '../user'

/**
 * Create the handlers necessary to enable google oauth authentication and session management.
 * After signin, a redirect request is sent pointing to the url which the user accessed the signin.
 * Check parameters documentation in server.ts.
 *
 * @param callbackUrl one of the authorized redirect urls enabled in the google oauth credential
 *                    (it can be relative if api url + router path + callback url matches the one of the redirect
 *                    urls and the /callback route provided by this router)
 * @param getUser transforms a profile into an user object
 * @param serializeUser transforms an user into its id
 * @param getUser transforms an id back to the user object
 */
export const createHandlers = (
    authentication: { clientId: string; clientSecret: string },
    callbackUrl: string,
    getUser: (profile: passport.Profile) => Promise<User>,
    serializeUser: (user: User) => Promise<string>,
    deserializeUser: (id: string) => Promise<User>,
    onUserAction: (User: User, action: Action) => void
) => {
    const strategy = new GoogleOAuth.Strategy(
        { clientID: authentication.clientId, clientSecret: authentication.clientSecret, callbackURL: callbackUrl },
        async (accessToken, refreshToken, profile, done) => done(undefined, await getUser(profile))
    )
    passport.serializeUser<User, string>(async (user, done) => done(undefined, await serializeUser(user)))
    passport.deserializeUser<User, string>(async (id, done) => done(undefined, await deserializeUser(id)))

    passport.use('google', strategy)

    const handlers: express.Handler[] = [
        cookieParser(),
        // set cookieSession secure option to true when https enabled
        cookieSession({ signed: false, maxAge: 24 * 60 * 60 * 1000, sameSite: 'none', secure: false }),
        passport.initialize(),
        passport.session()
    ]

    const router = express.Router()

    router.get(
        '/signin',
        (req, res, next) => {
            console.log('http', req.originalUrl, req.headers.referer)
            // set referer url cookie to remember client referer in case of CORS access
            res.cookie('referer', req.headers.referer ?? '/', { sameSite: 'none' })
            next()
        },
        passport.authenticate('google', { scope: ['profile', 'email'] })
    )

    router.get('/callback', passport.authenticate('google'), (req, res) => {
        const user = req.user as User
        console.log('http', req.originalUrl, req.cookies['referer'])
        onUserAction(user, { date: new Date(), name: 'signin', payload: undefined })
        // redirect to referer url set in cookie in /signin route
        res.redirect(req.cookies['referer'])
    })

    router.get('/signout', (req, res) => {
        const user = req.user as User
        console.log('http', req.originalUrl, req.user)
        onUserAction(user, { date: new Date(), name: 'signout', payload: undefined })
        req.logOut()
        res.redirect(req.headers.referer ?? '/')
    })

    router.get('/user', (req, res) => {
        console.log('http', req.originalUrl, req.user)
        res.send(req.user)
    })

    return { handlers, router }
}
