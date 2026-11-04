import copy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from scripts import impact_plan as impact
from scripts import ci_required
from scripts.ci_evidence import equivalent

BASE = 'a' * 40
HEAD = 'b' * 40
API = 'ravenroot/ravenroot-application-api'
CORE = 'ravenroot/ravenroot-core'
MAIL = 'ravenroot/ravenroot-extensions/ravenroot-mail'
OTHER = 'ravenroot/ravenroot-extensions/ravenroot-telegram'
SERVER = 'ravenroot/ravenroot-server'
SQLITE = 'ravenroot/ravenroot-persistence-sqlite'
DIST = 'ravenroot/ravenroot-distribution'
PACK = 'ravenroot/ravenroot-extensions/ravenroot-extensions-all'
GRAPH = {API: set(), CORE: {API}, MAIL: {API}, OTHER: {API}, SERVER: {CORE}, SQLITE: {CORE}, DIST: {SERVER}, PACK: {MAIL, OTHER}}

class ImpactPlanTest(unittest.TestCase):
    def plan(self, paths, event='pull_request', lane='release/0.8', force=False):
        with patch.object(impact, 'model', return_value=(GRAPH, set())), patch.object(impact, 'paths_between', return_value=paths), patch.object(impact, 'git', side_effect=lambda *args: HEAD if args[0] == 'rev-parse' and args[-1].startswith(HEAD) else BASE):
            return impact.make_plan(BASE, HEAD, event, lane, force)

    def test_leaf_excludes_unrelated_tests_and_preserves_pack_consumer(self):
        p = self.plan([MAIL + '/src/main/java/Mail.java'])
        self.assertFalse(p['full'])
        self.assertIn(MAIL, p['test'])
        self.assertIn(PACK, p['test'])
        self.assertNotIn(OTHER, p['test'])
        self.assertIn(OTHER, p['build'], 'pack build still needs all current-candidate prerequisites')
        self.assertIn('full-runtime-container-smoke', p['jobs'])
        self.assertNotIn('backend-test', p['jobs'])

    def test_ui_only_omits_backend_packaging_and_support(self):
        p = self.plan(['ravenroot/ravenroot-ui/src/App.vue'])
        self.assertFalse(p['full'])
        self.assertIn('full-ui-build', p['jobs'])
        self.assertNotIn('backend-build', p['jobs'])
        self.assertNotIn('backend-test', p['jobs'])
        self.assertIn('backend-build', p['omitted'])

    def test_persistence_runtime_consumer_closure(self):
        p = self.plan([SQLITE + '/src/main/java/Store.java'])
        self.assertIn(SERVER, p['test'])
        self.assertIn(DIST, p['test'])
        self.assertIn('full-ui-e2e', p['jobs'])

    def test_shared_api_widens_transitively(self):
        p = self.plan([API + '/src/main/java/Node.java'])
        self.assertTrue({MAIL, OTHER, CORE, SERVER, DIST}.issubset(p['test']))
        self.assertIn('backend-test', p['jobs'])

    def test_module_pom_narrows_but_parent_pom_falls_full(self):
        self.assertFalse(self.plan([MAIL + '/pom.xml'])['full'])
        self.assertTrue(self.plan(['ravenroot/pom.xml'])['full'])

    def test_tests_resources_are_owned(self):
        for path in ['/src/test/java/Test.java', '/src/main/resources/META-INF/services/Node']:
            self.assertIn(MAIL, self.plan([MAIL + path])['test'])

    def test_deleted_or_renamed_module_selects_full(self):
        for kind in ('deleted', 'renamed'):
            with self.subTest(kind=kind), patch.object(impact, 'model', side_effect=[(GRAPH, set()), ({API: set()}, set())]), patch.object(impact, 'paths_between', return_value=[MAIL + '/pom.xml']), patch.object(impact, 'git', return_value=BASE):
                self.assertTrue(impact.make_plan(BASE, HEAD, 'pull_request', 'release/0.8')['full'])

    def test_unknown_missing_empty_schedule_and_force_are_full(self):
        self.assertTrue(self.plan(['unregistered/file'])['full'])
        self.assertTrue(self.plan([])['full'])
        self.assertTrue(self.plan([MAIL + '/src/main/Mail.java'], event='schedule')['full'])
        self.assertTrue(self.plan([MAIL + '/src/main/Mail.java'], force=True)['full'])
        with patch.object(impact, 'git', side_effect=subprocess.CalledProcessError(128, ['git'])):
            # A missing baseline widens verification, an unknown candidate must fail.
            with self.assertRaises(subprocess.CalledProcessError):
                impact.make_plan('', HEAD, 'pull_request', 'release/0.8')

    def test_arbitrary_lane_is_refused(self):
        with self.assertRaises(ValueError):
            self.plan([MAIL + '/pom.xml'], lane='release/evil')

    def test_real_git_diff_preserves_both_sides_of_rename_and_deletion(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.org')
            Path(directory, 'old name').write_text('data'); Path(directory, 'deleted').write_text('bye')
            git('add', '.'); git('commit', '-qm', 'base'); base=git('rev-parse', 'HEAD')
            git('mv', 'old name', 'new name'); Path(directory, 'deleted').unlink()
            git('add', '.'); git('commit', '-qm', 'candidate'); head=git('rev-parse', 'HEAD')
            original = impact.git
            with patch.object(impact.subprocess, 'check_output', side_effect=lambda args, **kw: subprocess.run(['git', '-C', directory, *args[1:]], check=True, capture_output=True, text=True).stdout):
                self.assertEqual(set(impact.paths_between(base,head)), {'old name','new name','deleted'})

class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.now=datetime.now(timezone.utc)
        self.request={key: key+'-digest' for key in ('tree','base','plan','toolchain','runner_image','dependency_lock')}
        self.receipt=self.request | dict(repository='ravenroot-ai/ravenroot', workflow='.github/workflows/ci.yml', event='workflow_dispatch', conclusion='success', complete=True, trusted_api_receipt=True, completed_at=self.now.isoformat())

    def test_exact_complete_trusted_fresh_inputs_are_necessary(self):
        self.assertTrue(equivalent(self.receipt,self.request,self.now))
        for key in self.request:
            changed=self.request | {key:'changed'}
            self.assertFalse(equivalent(self.receipt,changed,self.now),key)

    def test_failed_foreign_stale_partial_missing_and_future_evidence_fail(self):
        for change in ({'conclusion':'failure'}, {'repository':'fork/repo'}, {'complete':False}, {'trusted_api_receipt':False}, {'runner_image':''}, {'completed_at':(self.now-timedelta(days=2)).isoformat()}, {'completed_at':(self.now+timedelta(hours=1)).isoformat()}):
            self.assertFalse(equivalent(self.receipt|change,self.request,self.now))

    def test_queue_base_advance_and_combined_features_are_not_exempt(self):
        for event in ('pull_request','merge_group'):
            receipt=self.receipt|{'event':event}
            self.assertTrue(equivalent(receipt,self.request,self.now))
            self.assertFalse(equivalent(receipt,self.request|{'base':'advanced'},self.now))
            self.assertFalse(equivalent(receipt,self.request|{'tree':'combined'},self.now))

if __name__ == '__main__': unittest.main()

class MaintenanceReleasePolicyTest(unittest.TestCase):
    def test_exact_maintenance_source_promotes_without_admitting_arbitrary_release_or_fork(self):
        from scripts.classify_main_change import classify, ClassificationError
        repo='ravenroot-ai/ravenroot'
        for source, owner, allowed in [('release/0.8',repo,True),('release/0.9',repo,False),('release/0.8','fork/ravenroot',False)]:
            arguments=dict(event_name='pull_request',base_ref='main',ref_name='x',labels={'release:patch'},paths=['ravenroot/ravenroot-core/src/main/X.java'],head_ref=source,head_repository=owner,repository=repo)
            if allowed:
                self.assertEqual(classify(**arguments)['tier'],'promotion')
                self.assertEqual(ci_required.verify_event('pull_request','main','x','promotion',source,owner,repo),[])
            else:
                with self.assertRaises(ClassificationError): classify(**arguments)
                self.assertTrue(ci_required.verify_event('pull_request','main','x','promotion',source,owner,repo))

    def test_release_authorization_accepts_exact_maintenance_patch_and_refuses_fork_or_other_lane(self):
        from scripts.release_contract import selected_pull_request, ReleaseContractError
        pr={'merge_commit_sha':HEAD,'merged_at':'2026-10-25T00:00:00Z','base':{'ref':'main'},'head':{'ref':'release/0.8','sha':BASE,'repo':{'full_name':'ravenroot-ai/ravenroot'}},'labels':[{'name':'release:patch'}]}
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'prs.json';p.write_text(json.dumps([pr]))
            self.assertEqual(selected_pull_request(p,HEAD),('patch','release/0.8'))
            for owner, source in [('fork/repo','release/0.8'),('ravenroot-ai/ravenroot','release/0.9')]:
                mutated=copy.deepcopy(pr);mutated['head']['ref']=source;mutated['head']['repo']['full_name']=owner;p.write_text(json.dumps([mutated]))
                with self.assertRaises(ReleaseContractError): selected_pull_request(p,HEAD)

    def test_prepare_source_is_allowlisted_before_writes(self):
        from scripts.prepare_release import prepare
        from scripts.release_contract import ReleaseContractError
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ReleaseContractError): prepare(Path(directory),'patch','release/evil')
            self.assertEqual(list(Path(directory).iterdir()),[])

    def test_required_jobs_cannot_be_omitted_or_forged(self):
        results={j:'success' for j in ci_required.GATED_JOBS}|{'release-classification':'success'}
        results['full-backend-tests']='skipped'
        self.assertTrue(ci_required.verify_results('full',results))
        forged={'baseline':BASE,'candidate':HEAD,'event':'merge_group','lane':'release/0.8','full':False,'jobs':[]}
        with patch('scripts.impact_plan.make_plan',return_value={'jobs':['backend-build']}):
            self.assertTrue(ci_required.verify_results('full',results,forged))

class MavenModelTest(unittest.TestCase):
    def test_managed_inherited_test_and_plugin_relationships_and_version_changes(self):
        prefix='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        poms={
            'ravenroot/pom.xml': prefix+'<groupId>ai.ravenroot</groupId><artifactId>parent</artifactId><version>0.8</version><dependencyManagement><dependencies><dependency><groupId>ai.ravenroot</groupId><artifactId>api</artifactId><version>${managed.version}</version></dependency></dependencies></dependencyManagement></project>',
            'ravenroot/api/pom.xml':prefix+'<parent><groupId>ai.ravenroot</groupId><artifactId>parent</artifactId><version>0.8</version></parent><artifactId>api</artifactId></project>',
            'ravenroot/consumer/pom.xml':prefix+'<parent><groupId>ai.ravenroot</groupId><artifactId>parent</artifactId><version>0.8</version></parent><artifactId>consumer</artifactId><dependencies><dependency><groupId>ai.ravenroot</groupId><artifactId>api</artifactId><scope>test</scope></dependency></dependencies><build><plugins><plugin><groupId>ai.ravenroot</groupId><artifactId>api</artifactId></plugin></plugins></build></project>'}
        def git(*args):
            if args[0]=='ls-tree': return '\n'.join(poms)
            return poms[args[1].split(':',1)[1]]
        with patch.object(impact,'git',side_effect=git):
            graph,_=impact.model(HEAD)
            self.assertIn('ravenroot/api',graph['ravenroot/consumer'])
            self.assertIn('ravenroot',graph['ravenroot/consumer'])
            self.assertNotIn('ravenroot/api',graph['ravenroot'], 'dependencyManagement supplies versions, not build edges')
            poms['ravenroot/consumer/pom.xml']=poms['ravenroot/consumer/pom.xml'].replace('<artifactId>api</artifactId>','<artifactId>${dynamic}</artifactId>')
            with self.assertRaises(ValueError): impact.model(HEAD)

    def test_union_of_base_candidate_dependencies_retains_removed_consumer_relationship(self):
        old={API:set(),CORE:{API},MAIL:{CORE}}
        new={API:set(),CORE:{API},MAIL:set()}
        with patch.object(impact,'model',side_effect=[(old,set()),(new,set())]), patch.object(impact,'paths_between',return_value=[CORE+'/pom.xml']),patch.object(impact,'git',return_value=HEAD):
            plan=impact.make_plan(BASE,HEAD,'pull_request','release/0.8')
            self.assertIn(MAIL,plan['test'])


class PromotionFreshnessTest(unittest.TestCase):
    def test_stale_failed_or_foreign_promotion_evidence_is_refused(self):
        now=datetime.now(timezone.utc)
        receipt={'path':'.github/workflows/ci.yml','head_sha':HEAD,'event':'workflow_dispatch','conclusion':'success','head_branch':'release/0.8','head_repository':{'full_name':'ravenroot-ai/ravenroot'},'updated_at':now.isoformat()}
        self.assertEqual(ci_required.verify_promotion_evidence({'workflow_runs':[receipt]},HEAD),[])
        for change in ({'updated_at':(now-timedelta(days=2)).isoformat()},{'conclusion':'failure'},{'head_repository':{'full_name':'fork/repo'}},{'head_branch':'release/0.9'}):
            self.assertTrue(ci_required.verify_promotion_evidence({'workflow_runs':[receipt|change]},HEAD))


class PromotionTreeTest(unittest.TestCase):
    def test_main_reuse_requires_exact_source_tree_not_only_source_commit(self):
        from scripts.classify_main_change import equivalent_merge_source
        for replies, expected in [([BASE+' '+HEAD,'tree','tree'],HEAD),([BASE+' '+HEAD,'source','combined'],''),([BASE],'')]:
            with patch('scripts.classify_main_change.subprocess.check_output',side_effect=replies):
                self.assertEqual(equivalent_merge_source(),expected)


class ConfiguratorPlanTest(unittest.TestCase):
    plan = ImpactPlanTest.plan
    def test_configurator_and_extension_carriers_select_independent_consumer(self):
        for path in ['ravenroot-configurator/src/App.tsx',MAIL+'/src/main/java/MailProfile.java']:
            plan=self.plan([path])
            self.assertFalse(plan['full'])
            self.assertIn('full-configurator',plan['jobs'])
            self.assertIn('full-configurator',plan['independent_build_scopes'])


class FullPlanEnumerationTest(unittest.TestCase):
    plan = ImpactPlanTest.plan
    def test_complete_records_all_applicable_build_and_test_owners(self):
        p=self.plan(['scripts/impact_plan.py'],force=True)
        self.assertTrue(p['full'])
        self.assertEqual(set(p['build']),set(GRAPH))
        self.assertEqual(set(p['test']),set(GRAPH))
    def test_licensed_profile_change_cannot_silently_pass_default_full(self):
        with self.assertRaises(RuntimeError): self.plan(['ravenroot/ravenroot-akka/src/main/X.java'])


class UnknownModelFallbackTest(unittest.TestCase):
    def test_unresolved_conditional_profile_records_explicit_complete_scope(self):
        with patch.object(impact,'model',side_effect=ValueError('conditional profile')),patch.object(impact,'paths_between',return_value=[MAIL+'/pom.xml']),patch.object(impact,'git',return_value=HEAD):
            plan=impact.make_plan(BASE,HEAD,'pull_request','release/0.8')
            self.assertTrue(plan['full'])
            self.assertEqual(plan['build'],['@complete-reactor-unresolved-model'])
            self.assertEqual(plan['test'],['@complete-reactor-unresolved-model'])


class RealModelConsumerPlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head = impact.git('rev-parse', 'HEAD')
        cls.graph, cls.files = impact.model(cls.head)

    def real_plan(self, path, event='merge_group'):
        # Only the one-file event range is synthetic; immutable real POM graph is unchanged.
        with patch.object(impact, 'model', return_value=(self.graph, self.files)), patch.object(impact, 'paths_between', return_value=[path]):
            return impact.make_plan(self.head, self.head, event, 'dev')

    def test_adapter_and_sample_cannot_omit_executed_support_prerequisites(self):
        for owner in ('ravenroot-adapter-anthropic', 'ravenroot-sample'):
            for event in ('pull_request', 'merge_group'):
                with self.subTest(owner=owner, event=event):
                    path = next(path for path in sorted(self.files) if path.startswith(owner + '/src/main/java/') and path.endswith('.java'))
                    plan = self.real_plan(path, event)
                    self.assertFalse(plan['full'])
                    self.assertIn('backend-test', plan['jobs'])
                    actual = impact.closure(impact.SUPPORT, self.graph)
                    self.assertEqual(plan['independent_build_scopes']['backend-test'], sorted(impact.SUPPORT))
                    self.assertTrue(actual.issubset(plan['build']), sorted(actual - set(plan['build'])))
                    execution = plan['support_execution']
                    self.assertEqual(set(execution['install_and_test'] + execution['verify']), impact.SUPPORT)
                    self.assertEqual(set(execution['current_candidate_prerequisites']), actual)
                    self.assertEqual(execution['sample_exec'], {'project': 'ravenroot-sample', 'engine': 'pekko', 'args': 'hello ravenroot'})
                    self.assertEqual(plan['main_reactor_tests'], [m for m in plan['test'] if m.startswith('ravenroot/')])
                    self.assertIn('ravenroot/ravenroot-server', plan['build'])
                    self.assertIn('ravenroot/ravenroot-pekko', plan['build'])
                    self.assertNotIn('full-configurator', plan['jobs'])
                    # Telegram is not a prerequisite; dev server tests do consume mail.
                    self.assertNotIn(OTHER, plan['build'])

    def test_every_explicit_configurator_carrier_has_candidate_and_queue_evidence(self):
        source = (impact.ROOT / '.github/workflows/configurator.yml').read_text()
        paths = [line.strip().removeprefix('- ') for line in source.splitlines()
                 if line.strip().startswith('- ravenroot/') and line.strip().endswith('.java') and '*' not in line]
        self.assertTrue(impact.CONFIGURATOR_CARRIERS.issubset(paths))
        self.assertGreater(len(paths), 30)
        for path in paths:
            self.assertIn(path, self.files)
            for event in ('pull_request', 'merge_group'):
                with self.subTest(path=path, event=event):
                    plan = self.real_plan(path, event)
                    self.assertFalse(plan['full'])
                    self.assertIn('full-configurator', plan['jobs'])
                    self.assertNotIn('full-configurator', plan['omitted'])

    def test_unrelated_telegram_leaf_does_not_inherit_configurator_or_support(self):
        for event in ('pull_request', 'merge_group'):
            plan = self.real_plan(OTHER + '/src/main/java/Telegram.java', event)
            self.assertFalse(plan['full'])
            self.assertNotIn('backend-test', plan['jobs'])
            self.assertNotIn('full-configurator', plan['jobs'])
            self.assertNotIn('backend-test', plan['independent_build_scopes'])


class DevelopmentPlanTest(ImpactPlanTest):
    def plan(self, paths, event='pull_request', lane='dev', force=False):
        return super().plan(paths, event, lane, force)

    def test_dev_and_maintenance_select_the_same_applicable_work(self):
        for event in ('pull_request', 'merge_group', 'workflow_dispatch'):
            for path in (MAIL + '/src/main/java/Mail.java', API + '/src/main/java/Node.java', 'ravenroot/ravenroot-ui/src/App.vue'):
                with self.subTest(event=event, path=path):
                    dev = self.plan([path], event=event)
                    maintenance = self.plan([path], event=event, lane='release/0.8')
                    self.assertFalse(dev['full'])
                    for key in ('jobs', 'build', 'test', 'integrations'):
                        self.assertEqual(dev[key], maintenance[key])

    def test_gate_recomputes_dev_omissions_and_requires_selected_work(self):
        plan = self.plan(['ravenroot/ravenroot-ui/src/App.vue'])
        results = {job: ('success' if job in plan['jobs'] else 'skipped') for job in ci_required.GATED_JOBS}
        results['release-classification'] = 'success'
        with patch('scripts.impact_plan.make_plan', return_value=plan):
            self.assertEqual(ci_required.verify_results('full', results, plan), [])
            results['full-ui-e2e'] = 'skipped'
            self.assertTrue(ci_required.verify_results('full', results, plan))
            forged = copy.deepcopy(plan)
            forged['jobs'].remove('full-ui-e2e')
            self.assertTrue(ci_required.verify_results('full', results, forged))


class DevelopmentWorkflowCoverageTest(unittest.TestCase):
    def test_selected_harness_preserves_dev_browser_regressions(self):
        workflow = ci_required.job_blocks(ci_required.WORKFLOW.read_text())
        harness = workflow['full-ui-e2e-harness']
        for consumer in ('FirstRunPersistenceBrowserTest', 'DeploymentHistoryBrowserTest',
                         'EmbedBrowserPlaywrightIntegrationTest', 'LakehouseExampleBrowserTest',
                         'SagaAuthoringBrowserTest'):
            self.assertIn(consumer, harness)
        native = workflow['full-backend-tests'].split('- name: Retain sanitized native Kubernetes results', 1)[1]
        self.assertIn("if: always() && steps.backend-scope.outputs.native == 'true'", native)
        self.assertIn('KubernetesWorkspaceGraphNativeTest.json', native)

    def test_schedule_and_explicit_complete_override_cannot_disappear(self):
        contents = ci_required.WORKFLOW.read_text()
        self.assertIn('force_complete:', contents)
        self.assertIn('[ "$EVENT_NAME" = schedule ]', contents)
        self.assertIn('extra+=(--force-complete)', contents)
        self.assertIn('schedule:', contents)


class DevelopmentConsumerClosureTest(RealModelConsumerPlanTest):
    def test_dev_mail_consumer_reaches_history_harness_and_support(self):
        path = next(path for path in sorted(self.files) if path.startswith(MAIL + '/src/main/java/') and path.endswith('.java'))
        plan = self.real_plan(path)
        self.assertFalse(plan['full'])
        self.assertIn(SERVER, plan['test'])
        self.assertIn('backend-test', plan['jobs'])
        self.assertIn('full-ui-e2e-harness', plan['jobs'])
        self.assertIn('ravenroot/ravenroot-extensions/ravenroot-openapi-server',
                      plan['independent_build_scopes']['full-ui-e2e-harness'])


class NativePlanScopeTest(unittest.TestCase):
    plan = ImpactPlanTest.plan
    def test_native_selection_covers_shared_selected_and_complete_but_not_leaf_scopes(self):
        selected = self.plan([CORE + '/src/main/java/Runtime.java'], lane='dev')
        self.assertFalse(selected['full'])
        self.assertTrue(selected['native_acceptance'])
        self.assertIn('full-backend-tests', selected['jobs'])
        complete = self.plan([MAIL + '/src/main/java/Mail.java'], lane='dev', force=True)
        self.assertTrue(complete['full'])
        self.assertTrue(complete['native_acceptance'])
        self.assertIn('full-backend-tests', complete['jobs'])
        leaf = self.plan([MAIL + '/src/main/java/Mail.java'], lane='dev')
        self.assertFalse(leaf['full'])
        self.assertFalse(leaf['native_acceptance'])


class LicensedReleaseMetadataTest(unittest.TestCase):
    AKKA = 'ravenroot/ravenroot-akka'
    POM = AKKA + '/pom.xml'
    OLD = '0.8.0-alpha.1'
    NEW = '0.9.0-alpha.1'
    PREFIX = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'

    def documents(self, direct_version=False):
        result = {}
        for commit, version in ((BASE, self.OLD), (HEAD, self.NEW)):
            result[commit, 'ravenroot/pom.xml'] = (self.PREFIX
                + '<groupId>ai.ravenroot</groupId><artifactId>ravenroot-parent</artifactId>'
                + f'<version>{version}</version></project>')
            result[commit, self.POM] = (self.PREFIX
                + '<parent><groupId>ai.ravenroot</groupId><artifactId>ravenroot-parent</artifactId>'
                + f'<version>{version}</version></parent><artifactId>ravenroot-akka</artifactId>'
                + (f'<groupId>ai.ravenroot</groupId><version>{version}</version>' if direct_version else '')
                + '<dependencies><dependency><groupId>com.typesafe.akka</groupId>'
                + '<artifactId>akka-actor-typed_2.13</artifactId><version>${akka.version}</version>'
                + '</dependency></dependencies></project>')
        return result

    def plan(self, documents, paths=None, force=True, lane='dev'):
        def git(*args):
            if args[0] == 'show':
                commit, path = args[1].split(':', 1)
                if (commit, path) not in documents:
                    raise subprocess.CalledProcessError(128, ['git', *args])
                return documents[commit, path]
            if args[0] == 'rev-parse':
                return HEAD if args[-1].startswith(HEAD) else BASE
            return BASE
        with patch.object(impact, 'git', side_effect=git), \
             patch.object(impact, 'paths_between', return_value=paths or [self.POM]), \
             patch.object(impact, 'model', return_value=(GRAPH, set())):
            return impact.make_plan(BASE, HEAD, 'workflow_dispatch', lane, force)

    def test_canonical_parent_and_optional_direct_project_versions_preserve_complete_default(self):
        for direct in (False, True):
            for lane in ('dev', 'release/0.8'):
                with self.subTest(direct=direct, lane=lane):
                    plan = self.plan(self.documents(direct), lane=lane)
                    self.assertTrue(plan['full'])
                    self.assertEqual(set(plan['jobs']), set(ci_required.GATED_JOBS))
                    self.assertNotIn(self.AKKA, plan['build'])
                    self.assertNotIn(self.AKKA, plan['test'])
                    self.assertTrue(any('metadata only; licensed profile remains excluded' in r for r in plan['reasons']))

    def test_meaningful_optional_pom_changes_refuse_even_forced_complete(self):
        mutations = {
            'external dependency version': lambda s: s.replace('${akka.version}', '2.10.1'),
            'dependency coordinate': lambda s: s.replace('com.typesafe.akka', 'other.group'),
            'project coordinate': lambda s: s.replace('<artifactId>ravenroot-akka</artifactId>', '<artifactId>other</artifactId>'),
            'parent coordinate': lambda s: s.replace('<artifactId>ravenroot-parent</artifactId>', '<artifactId>other-parent</artifactId>'),
            'parent group': lambda s: s.replace('<groupId>ai.ravenroot</groupId>', '<groupId>other.group</groupId>'),
            'repository': lambda s: s.replace('</project>', '<repositories><repository><id>licensed</id><url>https://example.org</url></repository></repositories></project>'),
            'profile activation': lambda s: s.replace('</project>', '<profiles><profile><id>akka</id><activation><activeByDefault>true</activeByDefault></activation></profile></profiles></project>'),
            'arbitrary property': lambda s: s.replace('</project>', '<properties><akka.version>2.10.1</akka.version></properties></project>'),
            'noncanonical parent version': lambda s: s.replace(self.NEW, '9.9.9', 1),
            'unresolved parent version': lambda s: s.replace(self.NEW, '${revision}', 1),
            'duplicate version': lambda s: s.replace(f'<version>{self.NEW}</version>', f'<version>{self.NEW}</version><version>{self.NEW}</version>', 1),
            'external XML declaration': lambda s: '<!DOCTYPE project SYSTEM "https://example.org/license.dtd">' + s,
            'processing instruction': lambda s: s.replace('</project>', '<?configuration licensed?></project>'),
            'malformed XML': lambda s: s.removesuffix('</project>'),
        }
        for name, mutate in mutations.items():
            with self.subTest(change=name):
                docs = self.documents(); docs[HEAD, self.POM] = mutate(docs[HEAD, self.POM])
                with self.assertRaises(RuntimeError):
                    self.plan(docs)

    def test_dependency_versions_equal_to_product_versions_are_still_not_metadata(self):
        docs = self.documents()
        for commit, version in ((BASE, self.OLD), (HEAD, self.NEW)):
            docs[commit, self.POM] = docs[commit, self.POM].replace('${akka.version}', version)
        with self.assertRaises(RuntimeError): self.plan(docs)

    def test_noncanonical_direct_project_version_or_root_identity_refuses(self):
        docs = self.documents(direct_version=True)
        # Replace the direct project version without touching the canonical parent field.
        docs[HEAD, self.POM] = docs[HEAD, self.POM].replace(f'<groupId>ai.ravenroot</groupId><version>{self.NEW}</version>', '<groupId>ai.ravenroot</groupId><version>9.9.9</version>')
        with self.assertRaises(RuntimeError): self.plan(docs)
        docs = self.documents()
        docs[HEAD, 'ravenroot/pom.xml'] = docs[HEAD, 'ravenroot/pom.xml'].replace('ai.ravenroot', 'other.group')
        with self.assertRaises(RuntimeError): self.plan(docs)

    def test_missing_pom_and_version_only_plus_licensed_source_changes_refuse(self):
        for missing in ((BASE, self.POM), (HEAD, self.POM), (HEAD, 'ravenroot/pom.xml')):
            docs = self.documents(); del docs[missing]
            with self.subTest(missing=missing), self.assertRaises(RuntimeError): self.plan(docs)
        for paths in ([self.AKKA + '/src/main/java/Changed.java'],
                      [self.POM, self.AKKA + '/src/main/java/Changed.java']):
            with self.subTest(paths=paths), self.assertRaises(RuntimeError):
                self.plan(self.documents(), paths=paths)
