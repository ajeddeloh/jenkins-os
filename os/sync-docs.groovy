#!groovy

properties([
    parameters([
        string(name: 'PROJECT',
               defaultValue: '',
               description: 'Which project to sync (e.g. ignition)'),
        string(name: 'RELEASE',
               defaultValue: "",
               description: 'Which release to sync (e.g. 0.33.0)'),
    ])
])

/* The following might want to be configured.  */
def username = 'coreosbot'
def loginCreds = '2b710187-52cc-4e5b-a020-9cae59519baa'
def sshCreds = 'f40af2c1-0f07-41c4-9aad-5014dd213a3e'
//def gsCreds = '9b77e4af-a9cb-47c8-8952-f375f0b48596'

def docsProject = 'coreos-inc/coreos-pages'
def pagesProject = 'coreos-inc/pages'

def project = params.PROJECT
def release = params.RELEASE
def branch = "sync-${project}-${release}"

def gitAuthor = 'Jenkins OS'
def gitEmail = 'team-os@coreos.com'

def forkProject = "${username}/${docsProject.split('/')[-1]}"

def docsUrl = "ssh://git@github.com/${docsProject}.git"
def forkUrl = "ssh://git@github.com/${forkProject}.git"
def pagesUrl = "ssh://git@github.com/${pagesProject}.git"

node('amd64 && coreos && sudo') {
    stage('SDK') {
        copyArtifacts fingerprintArtifacts: true,
                      projectName: '/mantle/master-builder',
                      selector: lastSuccessful()
        copyArtifacts filter: 'keyring.asc',
                      fingerprintArtifacts: true,
                      projectName: '/os/keyring',
                      selector: lastSuccessful()
        sh '''#!/bin/bash -ex
# Set up GPG for verifying manifest tags.
export GNUPGHOME="${PWD}/gnupg"
rm -fr "${GNUPGHOME}"
trap 'rm -fr "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import keyring.asc

# Find the most recent alpha version.
bin/gangue get --json-key=/dev/null \
    gs://alpha.release.core-os.net/amd64-usr/current/version.txt
. version.txt

# Create the SDK used by the most recent alpha without updating.
bin/cork create \
    --replace --verify --verify-signature --verbose \
    --manifest-branch=refs/tags/v${COREOS_VERSION} \
    --manifest-name=release.xml
'''  /* Editor quote safety: ' */
    }

    sshagent([sshCreds]) {
        /* The git step ignores the sshagent environment, so script it.  */
        stage('SCM') {
            sh """#!/bin/bash -ex
rm -fr coreos-pages pages
git clone ${docsUrl} coreos-pages
git clone --depth=1 ${pagesUrl} pages
git -C coreos-pages config user.name '${gitAuthor}'
git -C coreos-pages config user.email '${gitEmail}'
git -C coreos-pages checkout -B ${branch}
"""  /* Editor quote safety: " */
        }

        stage('Build') {
            sh '''#!/bin/bash -ex
bin/cork enter --bind-gpg-agent=false -- \
    cargo build --package=sync --release --verbose \
        --manifest-path=/mnt/host/source/pages/sync/Cargo.toml
ln -fns target/release/sync  pages/sync/sync
'''  /* Editor quote safety: ' */
        }

        stage('Sync') {
            sh """#!/bin/bash -ex
(cd pages/sync ; exec ./sync -p ${project} -r ${release} )

git -C coreos-pages add .
git -C coreos-pages commit -am '${project}: sync ${version}'
git -C coreos-pages push -f ${forkUrl} ${branch}
"""  /* Editor quote safety: " */
        }
    }

    stage('PR') {
        withCredentials([string(credentialsId: loginCreds, variable: 'pat')]) {
            createPullRequest token: pat,
                upstreamProject: docsProject,
                sourceOwner: username,
                sourceBranch: branch,
                title: "${project}: sync ${version}",
                message: "From: ${env.BUILD_URL}"
        }
    }
}
