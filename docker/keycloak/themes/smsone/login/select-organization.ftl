<#import "template.ftl" as layout>
<#--
    Override of theme/base/login/select-organization.ftl. Fires during
    the OAuth flow when the signing-in user belongs to multiple
    Keycloak organizations (we map one Keycloak org per SMSOne
    community). The upstream default is a bare bulleted list with
    "Select an organization" wording; ours uses SMSOne branding
    ("community" not "organization") and renders each option as a
    keyboard-accessible radio card matching the look of our
    select-authenticator screen.

    Form contract: the chosen org alias must end up in the `kc.org`
    hidden input and the form posted to `url.loginAction`. Each card
    sets `kc.org` on click and submits — keyboard users can also
    Tab between them and hit Enter on the wrapping button.
-->
<@layout.registrationLayout; section>
    <#if section = "form">
        <h1 class="smsone-title">Choose your community</h1>
        <p class="smsone-subtitle">
            You're a member of more than one community on ${realm.displayName!'SMSOne'} —
            pick the one you'd like to sign into.
        </p>

        <form id="kc-select-organization-form" action="${url.loginAction}" method="post">
            <input type="hidden" name="kc.org" value="" />

            <ul style="list-style: none; padding: 0; margin: 0 0 2rem 0;">
                <#list user.organizations as organization>
                    <li style="margin-bottom: 0.75rem;">
                        <button
                            type="button"
                            id="organization-${organization.alias}"
                            onclick="document.forms['kc-select-organization-form']['kc.org'].value = '${organization.alias}'; document.forms['kc-select-organization-form'].requestSubmit();"
                            style="width: 100%; display: flex; align-items: center; border: 1px solid var(--smsone-border); border-radius: 8px; padding: 1.125rem 1.25rem; background: #fff; cursor: pointer; transition: all 0.15s ease; text-align: left;"
                            onmouseover="this.style.borderColor='var(--smsone-primary)'; this.style.background='rgba(69, 139, 158, 0.04)';"
                            onmouseout="this.style.borderColor='var(--smsone-border)'; this.style.background='#fff';"
                        >
                            <div style="margin-right: 1.125rem; color: var(--smsone-primary); display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; background: rgba(69, 139, 158, 0.1); border-radius: 8px; flex-shrink: 0;">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
                                    <polyline points="9 22 9 12 15 12 15 22"></polyline>
                                </svg>
                            </div>
                            <div style="flex: 1; min-width: 0;">
                                <div style="font-weight: 600; font-size: 0.9375rem; color: var(--smsone-text-main); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
                                    ${organization.name!organization.alias}
                                </div>
                                <#if organization.description??>
                                    <div style="font-size: 0.8125rem; color: var(--smsone-text-muted); margin-top: 0.1875rem;">
                                        ${organization.description}
                                    </div>
                                </#if>
                            </div>
                            <div style="color: var(--smsone-border); margin-left: 0.75rem;">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <polyline points="9 18 15 12 9 6"></polyline>
                                </svg>
                            </div>
                        </button>
                    </li>
                </#list>
            </ul>
        </form>
    </#if>
</@layout.registrationLayout>
