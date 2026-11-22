# Merged into `build-tool`

这个原子已并入 [`build-tool`](https://github.com/hummerstudio/jenkins-atom-build-tool)：三个类原本只差三个常量
（二进制名、`*_HOME`、默认 goals），差异现在是一个参数。

原调用：

```groovy
runAtom(atom: 'maven', version: '1.latest', inputs: [...])
```

现在的等价写法：

```groovy
runAtom(atom: 'build-tool', version: '1.latest', inputs: [tool: 'maven', ...])
```

输入名不变（`goals` / `options` / `home` / `failOnError`）。本仓库只读保留，便于历史构建追溯。
