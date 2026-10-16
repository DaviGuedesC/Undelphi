// Compila cada .vue de uma pasta (template + script setup + style) e falha se algum tiver erro.
// Uso: node check.mjs <pasta-com-vue>
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { parse, compileScript, compileTemplate, compileStyle } from '@vue/compiler-sfc'

const dir = process.argv[2]
if (!dir) {
  console.error('uso: node check.mjs <pasta>')
  process.exit(2)
}

let falhas = 0
const arquivos = readdirSync(dir).filter((f) => f.endsWith('.vue'))
for (const nome of arquivos) {
  const source = readFileSync(join(dir, nome), 'utf8')
  const erros = []
  const { descriptor, errors } = parse(source, { filename: nome })
  erros.push(...errors.map((e) => e.message || String(e)))
  if (!erros.length) {
    try {
      const script = compileScript(descriptor, { id: nome, inlineTemplate: true })
      if (!script.content) erros.push('script vazio')
    } catch (e) {
      erros.push('script: ' + e.message)
    }
    if (descriptor.template) {
      const tpl = compileTemplate({ source: descriptor.template.content, filename: nome, id: nome })
      erros.push(...tpl.errors.map((e) => 'template: ' + (e.message || String(e))))
    }
    for (const style of descriptor.styles) {
      const css = compileStyle({ source: style.content, filename: nome, id: 'data-v-' + nome, scoped: style.scoped })
      erros.push(...css.errors.map((e) => 'style: ' + (e.message || String(e))))
    }
  }
  if (erros.length) {
    falhas++
    console.log(`FALHOU ${nome}`)
    erros.forEach((e) => console.log('  - ' + e))
  } else {
    console.log(`ok     ${nome}`)
  }
}
console.log(`${arquivos.length - falhas}/${arquivos.length} telas compilaram`)
process.exit(falhas ? 1 : 0)
